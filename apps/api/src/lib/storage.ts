import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { dirname, join, normalize, resolve, sep } from 'node:path';
import {
  DeleteObjectCommand,
  GetObjectCommand,
  PutObjectCommand,
  S3Client
} from '@aws-sdk/client-s3';
import { env } from '../env.js';
import { ApiError } from './errors.js';
import { logger } from './logger.js';

/**
 * Receipt images live behind this interface so the local driver can serve a
 * checkout with no cloud account, and production can point at S3 without a line
 * of route code changing.
 */
export interface StoredObject {
  key: string;
  bytes: number;
  contentType: string;
}

export interface Storage {
  put(key: string, body: Buffer, contentType: string): Promise<StoredObject>;
  get(key: string): Promise<Buffer>;
  delete(key: string): Promise<void>;
  /** A URL the client can fetch, or null when the object must be streamed by the API. */
  publicUrl(key: string): string | null;
}

/** Keys are built by us, but validate anyway — a `..` here would read the filesystem. */
function assertSafeKey(key: string): void {
  const clean = normalize(key);
  if (clean.startsWith('..') || clean.startsWith(sep) || clean.includes(`..${sep}`)) {
    throw ApiError.badRequest('INVALID_KEY', 'That storage key is not allowed.');
  }
}

class LocalStorage implements Storage {
  private readonly root = resolve(process.cwd(), env.STORAGE_LOCAL_DIR);

  async put(key: string, body: Buffer, contentType: string): Promise<StoredObject> {
    assertSafeKey(key);
    const path = join(this.root, key);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, body);
    return { key, bytes: body.byteLength, contentType };
  }

  async get(key: string): Promise<Buffer> {
    assertSafeKey(key);
    try {
      return await readFile(join(this.root, key));
    } catch {
      throw ApiError.notFound('That receipt image');
    }
  }

  async delete(key: string): Promise<void> {
    assertSafeKey(key);
    await unlink(join(this.root, key)).catch(() => undefined);
  }

  publicUrl(): string | null {
    // Served through the API so it stays behind auth.
    return null;
  }
}

/**
 * Any S3-compatible object store, which is all three of the clouds worth deploying on:
 * AWS S3 natively, Google Cloud Storage through its S3-compatible XML API with an HMAC
 * key, and Oracle Object Storage through its S3 compatibility endpoint. One driver, so
 * moving between them is a change of environment variables and nothing else.
 *
 * This matters more than it sounds: the local driver writes to the container filesystem,
 * which is ephemeral everywhere except a VM with a disk attached. On Cloud Run, App Runner
 * or Fargate, `STORAGE_DRIVER=local` means every receipt is lost when the instance is
 * recycled, and nothing tells you until someone reopens an old expense.
 */
class S3Storage implements Storage {
  /**
   * Built on first use, not in the constructor. `storage` is a module-level singleton, so
   * a client built eagerly would be constructed by every import — including the test
   * suite, which never touches S3.
   */
  private client: S3Client | null = null;
  private readonly bucket: string;

  constructor() {
    if (!env.S3_BUCKET) {
      throw new Error('STORAGE_DRIVER=s3 needs S3_BUCKET to be set.');
    }
    this.bucket = env.S3_BUCKET;
    logger.info(
      { bucket: this.bucket, endpoint: env.S3_ENDPOINT ?? 'aws', region: env.S3_REGION },
      'using S3-compatible storage'
    );
  }

  private get s3(): S3Client {
    if (this.client) return this.client;
    this.client = new S3Client({
      region: env.S3_REGION ?? 'us-east-1',
      // Set for anything that is not AWS. GCS and Oracle both need it.
      ...(env.S3_ENDPOINT ? { endpoint: env.S3_ENDPOINT } : {}),
      // Virtual-host style addressing needs per-bucket DNS, which S3-compatible services
      // generally do not provide, so path style is the safe default away from AWS.
      forcePathStyle: env.S3_FORCE_PATH_STYLE ?? Boolean(env.S3_ENDPOINT),
      // Explicit keys when given; otherwise the SDK's own chain, which is what picks up an
      // IAM role on EC2/ECS or a workload identity on GKE — no secrets to store at all.
      ...(env.S3_ACCESS_KEY_ID && env.S3_SECRET_ACCESS_KEY
        ? {
            credentials: {
              accessKeyId: env.S3_ACCESS_KEY_ID,
              secretAccessKey: env.S3_SECRET_ACCESS_KEY
            }
          }
        : {})
    });
    return this.client;
  }

  async put(key: string, body: Buffer, contentType: string): Promise<StoredObject> {
    assertSafeKey(key);
    await this.s3.send(
      new PutObjectCommand({
        Bucket: this.bucket,
        Key: key,
        Body: body,
        ContentType: contentType,
        // Belt and braces: the bucket should be private anyway, but a receipt must never
        // be readable by anyone holding the URL.
        ACL: undefined
      })
    );
    return { key, bytes: body.byteLength, contentType };
  }

  async get(key: string): Promise<Buffer> {
    assertSafeKey(key);
    try {
      const result = await this.s3.send(
        new GetObjectCommand({ Bucket: this.bucket, Key: key })
      );
      const body = result.Body;
      if (!body) throw new Error('empty body');
      // transformToByteArray is on the SDK's stream mixin in Node, the browser and Lambda.
      return Buffer.from(await body.transformToByteArray());
    } catch (error) {
      const name = (error as { name?: string }).name;
      if (name === 'NoSuchKey' || name === 'NotFound') {
        throw ApiError.notFound('That receipt image');
      }
      logger.error({ err: error, key }, 'could not read an object from S3');
      throw error;
    }
  }

  async delete(key: string): Promise<void> {
    assertSafeKey(key);
    try {
      await this.s3.send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
    } catch (error) {
      // A delete that fails because it is already gone has done its job.
      const name = (error as { name?: string }).name;
      if (name !== 'NoSuchKey' && name !== 'NotFound') {
        logger.warn({ err: error, key }, 'could not delete an object from S3');
      }
    }
  }

  publicUrl(): string | null {
    // Always null, on purpose. A receipt is a photograph of somebody's restaurant bill,
    // with their card's last four digits on it as often as not; it is served through the
    // authenticated route so that access is checked every time. Returning a direct bucket
    // URL here would be the one line that makes every receipt public to anyone who can
    // guess a key.
    return null;
  }
}

export const storage: Storage = env.STORAGE_DRIVER === 's3' ? new S3Storage() : new LocalStorage();

/** `receipts/<userId>/<yyyy-mm>/<checksum>.jpg` — sharded by user and month. */
export function receiptKey(userId: string, checksum: string, extension: string): string {
  const month = new Date().toISOString().slice(0, 7);
  return `receipts/${userId}/${month}/${checksum}${extension.startsWith('.') ? extension : `.${extension}`}`;
}
