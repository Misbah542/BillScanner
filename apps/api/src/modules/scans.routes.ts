import { Router } from 'express';
import multer from 'multer';
import { z } from 'zod';
import { env } from '../env.js';
import { ApiError } from '../lib/errors.js';
import { prisma } from '../lib/prisma.js';
import { storage } from '../lib/storage.js';
import { requireAuth } from '../middleware/auth.js';
import { validateParams } from '../middleware/validate.js';
import { scanLimiter } from '../middleware/rateLimit.js';
import { enqueueScan, processScan } from '../services/scanService.js';
import { isTest } from '../env.js';

export const scansRouter = Router();
scansRouter.use(requireAuth);

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: env.SCAN_MAX_BYTES, files: 1 }
});

const IdParam = z.object({ id: z.string().uuid() });

/**
 * `POST /v1/scans` — upload a receipt photo.
 *
 * Returns 202 with a scan id immediately; the recognition happens in a worker. The
 * client shows its progress screen and either polls `GET /v1/scans/:id` or waits
 * for the `SCAN_READY` push.
 *
 * Recognition is entirely server-side by design: the provider can be swapped, the
 * parsing rules can be fixed for every user without an app release, and the phone
 * does no work. Send `Idempotency-Key` so a retried upload after a dropped
 * connection does not queue the same photo twice.
 */
scansRouter.post('/', scanLimiter, upload.single('image'), async (req, res, next) => {
  try {
    const file = req.file;
    if (!file) {
      throw ApiError.badRequest('NO_IMAGE', 'Attach the receipt photo as the `image` field.');
    }

    const idempotencyKey =
      typeof req.headers['idempotency-key'] === 'string'
        ? req.headers['idempotency-key'].slice(0, 128)
        : undefined;

    const result = await enqueueScan(
      req.user!.id,
      { buffer: file.buffer, mimetype: file.mimetype, size: file.size },
      idempotencyKey
    );

    // In tests there is no worker process, so run it inline to keep the suite
    // hermetic. Production always goes through the queue.
    if (isTest) await processScan(result.scanId);

    const scan = await prisma.scan.findUniqueOrThrow({
      where: { id: result.scanId },
      select: scanSelect
    });

    res.status(result.deduplicated ? 200 : 202).json({
      scan: serializeScan(scan),
      deduplicated: result.deduplicated,
      pollAfterMs: 1200
    });
  } catch (error) {
    next(error);
  }
});

/** Poll target. `status` goes QUEUED -> PROCESSING -> SUCCEEDED | FAILED. */
scansRouter.get('/:id', validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const scan = await prisma.scan.findFirst({
      where: { id, userId: req.user!.id },
      select: scanSelect
    });
    if (!scan) throw ApiError.notFound('That scan');
    res.json({ scan: serializeScan(scan) });
  } catch (error) {
    next(error);
  }
});

/** The receipt image itself, streamed through the API so it stays behind auth. */
scansRouter.get('/:id/image', validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const scan = await prisma.scan.findFirst({
      where: { id, userId: req.user!.id },
      select: { assetKey: true, mimeType: true }
    });
    if (!scan) throw ApiError.notFound('That scan');

    const bytes = await storage.get(scan.assetKey);
    res.setHeader('content-type', scan.mimeType);
    res.setHeader('cache-control', 'private, max-age=86400');
    res.send(bytes);
  } catch (error) {
    next(error);
  }
});

/** Ask for another go at a scan that failed — a bad network read, a provider blip. */
scansRouter.post('/:id/retry', scanLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const scan = await prisma.scan.findFirst({
      where: { id, userId: req.user!.id },
      select: { id: true, status: true }
    });
    if (!scan) throw ApiError.notFound('That scan');
    if (scan.status === 'SUCCEEDED') {
      throw ApiError.conflict('ALREADY_DONE', 'That scan already succeeded.');
    }

    await prisma.scan.update({
      where: { id },
      data: { status: 'QUEUED', attempts: 0, errorCode: null, errorMessage: null }
    });
    await prisma.outboxEvent.create({ data: { topic: 'scan.process', payload: { scanId: id } } });
    if (isTest) await processScan(id);

    const fresh = await prisma.scan.findUniqueOrThrow({ where: { id }, select: scanSelect });
    res.status(202).json({ scan: serializeScan(fresh) });
  } catch (error) {
    next(error);
  }
});

const scanSelect = {
  id: true,
  status: true,
  provider: true,
  mimeType: true,
  bytes: true,
  confidence: true,
  parsed: true,
  errorCode: true,
  errorMessage: true,
  attempts: true,
  queuedAt: true,
  startedAt: true,
  finishedAt: true,
  expense: { select: { id: true } }
} as const;

function serializeScan(scan: {
  id: string;
  status: string;
  provider: string;
  mimeType: string;
  bytes: number;
  confidence: number | null;
  parsed: unknown;
  errorCode: string | null;
  errorMessage: string | null;
  attempts: number;
  queuedAt: Date;
  startedAt: Date | null;
  finishedAt: Date | null;
  expense: { id: string } | null;
}) {
  return {
    id: scan.id,
    status: scan.status,
    provider: scan.provider,
    confidence: scan.confidence,
    /** The ParsedReceipt from packages/shared, plus ranked category suggestions. */
    result: scan.status === 'SUCCEEDED' ? scan.parsed : null,
    error:
      scan.status === 'FAILED'
        ? { code: scan.errorCode, message: scan.errorMessage }
        : null,
    attempts: scan.attempts,
    expenseId: scan.expense?.id ?? null,
    imageUrl: `${env.PUBLIC_BASE_URL}/v1/scans/${scan.id}/image`,
    queuedAt: scan.queuedAt,
    startedAt: scan.startedAt,
    finishedAt: scan.finishedAt
  };
}
