import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { dirname, join, normalize, resolve, sep } from 'node:path';
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

class S3Storage implements Storage {
  constructor() {
    if (!env.S3_BUCKET) {
      throw new Error('STORAGE_DRIVER=s3 needs S3_BUCKET to be set.');
    }
    logger.info({ bucket: env.S3_BUCKET }, 'using S3 storage');
  }

  async put(): Promise<StoredObject> {
    throw ApiError.notImplemented(
      'S3_NOT_WIRED',
      'The S3 driver is a stub: add @aws-sdk/client-s3 and implement put/get/delete before setting STORAGE_DRIVER=s3.'
    );
  }

  async get(): Promise<Buffer> {
    throw ApiError.notImplemented('S3_NOT_WIRED', 'The S3 driver is not wired up yet.');
  }

  async delete(): Promise<void> {
    throw ApiError.notImplemented('S3_NOT_WIRED', 'The S3 driver is not wired up yet.');
  }

  publicUrl(key: string): string | null {
    if (!env.S3_ENDPOINT) return null;
    return `${env.S3_ENDPOINT.replace(/\/$/, '')}/${env.S3_BUCKET}/${key}`;
  }
}

export const storage: Storage = env.STORAGE_DRIVER === 's3' ? new S3Storage() : new LocalStorage();

/** `receipts/<userId>/<yyyy-mm>/<checksum>.jpg` — sharded by user and month. */
export function receiptKey(userId: string, checksum: string, extension: string): string {
  const month = new Date().toISOString().slice(0, 7);
  return `receipts/${userId}/${month}/${checksum}${extension.startsWith('.') ? extension : `.${extension}`}`;
}
