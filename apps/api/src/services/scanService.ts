import { parseReceiptText } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { logger } from '../lib/logger.js';
import { sha256Buffer } from '../lib/crypto.js';
import { receiptKey, storage } from '../lib/storage.js';
import { env } from '../env.js';
import { OcrError, ocrProvider } from '../ocr/index.js';
import { enqueueNotification } from './notificationService.js';
import { suggestCategories } from './categoryService.js';

const EXTENSIONS: Record<string, string> = {
  'image/jpeg': '.jpg',
  'image/jpg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'image/heic': '.heic',
  'application/pdf': '.pdf'
};

export interface EnqueueResult {
  scanId: string;
  status: string;
  /** True when the same image (or idempotency key) had already been uploaded. */
  deduplicated: boolean;
}

/**
 * Accepts an upload and QUEUES it. The response comes back immediately with a scan
 * id; the client polls `GET /v1/scans/:id` (or waits for the push) while a worker
 * does the recognition.
 *
 * Doing it this way is what lets the pipeline scale: the request holds no OCR call,
 * so a Vision outage or a slow read cannot pile up open connections, and workers
 * scale independently of the API.
 */
export async function enqueueScan(
  userId: string,
  file: { buffer: Buffer; mimetype: string; size: number },
  idempotencyKey?: string
): Promise<EnqueueResult> {
  if (file.size > env.SCAN_MAX_BYTES) {
    throw ApiError.tooLarge(
      `That image is ${(file.size / 1024 / 1024).toFixed(1)} MB; the limit is ${(env.SCAN_MAX_BYTES / 1024 / 1024).toFixed(0)} MB.`
    );
  }
  const extension = EXTENSIONS[file.mimetype];
  if (!extension) {
    throw ApiError.badRequest(
      'UNSUPPORTED_MEDIA',
      `${file.mimetype} cannot be read. Send a JPEG, PNG, WebP, HEIC or PDF.`
    );
  }

  const checksum = sha256Buffer(file.buffer);

  // The same photo uploaded twice — a flaky connection, a retried request — must
  // not cost a second OCR call.
  const existing = await prisma.scan.findFirst({
    where: { userId, OR: [{ checksum }, ...(idempotencyKey ? [{ idempotencyKey }] : [])] },
    select: { id: true, status: true }
  });
  if (existing) {
    return { scanId: existing.id, status: existing.status, deduplicated: true };
  }

  const key = receiptKey(userId, checksum, extension);
  await storage.put(key, file.buffer, file.mimetype);

  const scan = await prisma.scan.create({
    data: {
      userId,
      status: 'QUEUED',
      assetKey: key,
      mimeType: file.mimetype,
      bytes: file.size,
      checksum,
      ...(idempotencyKey ? { idempotencyKey } : {}),
      provider: env.OCR_PROVIDER
    },
    select: { id: true, status: true }
  });

  await prisma.outboxEvent.create({
    data: { topic: 'scan.process', payload: { scanId: scan.id } }
  });

  return { scanId: scan.id, status: scan.status, deduplicated: false };
}

const MAX_ATTEMPTS = 3;

/**
 * Runs one scan through the pipeline: fetch the image, OCR it, hand the text to the
 * shared parser, suggest a category, and store the structured result for the client
 * to collect.
 *
 * Retryable provider failures go back on the queue; a rejected image fails for good
 * rather than burning attempts on something that will never parse.
 */
export async function processScan(scanId: string): Promise<void> {
  const scan = await prisma.scan.findUnique({ where: { id: scanId } });
  if (!scan) {
    logger.warn({ scanId }, 'scan vanished before processing');
    return;
  }
  if (scan.status === 'SUCCEEDED') return;

  await prisma.scan.update({
    where: { id: scanId },
    data: { status: 'PROCESSING', startedAt: new Date(), attempts: { increment: 1 } }
  });

  try {
    const image = await storage.get(scan.assetKey);
    const provider = ocrProvider();
    const result = await provider.recognize(image, scan.mimeType);

    const parsed = parseReceiptText(result.text, 'INR');
    const suggestions = await suggestCategories(scan.userId, {
      merchant: parsed.merchantName ?? null,
      itemNames: parsed.items.map((item) => item.name)
    });

    await prisma.$transaction(async (tx) => {
      await tx.scan.update({
        where: { id: scanId },
        data: {
          status: 'SUCCEEDED',
          provider: provider.name,
          rawText: result.text,
          providerRaw: (result.raw ?? null) as never,
          parsed: { ...parsed, suggestions } as never,
          confidence: parsed.confidence,
          finishedAt: new Date(),
          errorCode: null,
          errorMessage: null
        }
      });

      await enqueueNotification(tx, {
        userId: scan.userId,
        kind: 'SCAN_READY',
        title: parsed.merchantName ? `${parsed.merchantName} is ready` : 'Your bill is ready',
        body: `${parsed.items.length} item${parsed.items.length === 1 ? '' : 's'} read. Check it and split it.`,
        data: { scanId, itemCount: parsed.items.length, totalMinor: parsed.totals.totalMinor }
      });
    });

    logger.info(
      { scanId, items: parsed.items.length, confidence: parsed.confidence },
      'scan parsed'
    );
  } catch (error) {
    const ocr = error instanceof OcrError ? error : null;
    const retryable = ocr?.retryable ?? false;
    const attempts = scan.attempts + 1;
    const giveUp = !retryable || attempts >= MAX_ATTEMPTS;

    await prisma.scan.update({
      where: { id: scanId },
      data: {
        status: giveUp ? 'FAILED' : 'QUEUED',
        errorCode: ocr?.code ?? 'INTERNAL',
        errorMessage: (error as Error).message.slice(0, 500),
        ...(giveUp ? { finishedAt: new Date() } : {})
      }
    });

    if (giveUp) {
      await prisma.$transaction(async (tx) => {
        await enqueueNotification(tx, {
          userId: scan.userId,
          kind: 'SCAN_FAILED',
          title: 'That bill could not be read',
          body:
            ocr?.code === 'NO_TEXT_FOUND'
              ? 'No text came through. Try again with more light, or enter it by hand.'
              : 'Something went wrong reading it. You can enter it by hand.',
          data: { scanId, errorCode: ocr?.code ?? 'INTERNAL' }
        });
      });
      logger.error({ scanId, err: error }, 'scan failed for good');
    } else {
      await prisma.outboxEvent.create({
        data: {
          topic: 'scan.process',
          payload: { scanId },
          // Back off before trying again.
          availableAt: new Date(Date.now() + attempts * 15_000)
        }
      });
      logger.warn({ scanId, attempts }, 'scan failed, requeued');
    }
  }
}
