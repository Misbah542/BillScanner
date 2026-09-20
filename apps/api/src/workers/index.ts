import { logger } from '../lib/logger.js';
import { prisma } from '../lib/prisma.js';
import { processScan } from '../services/scanService.js';
import { deliverPush } from './push.js';

/**
 * A transactional-outbox worker.
 *
 * Every side effect — running OCR on a scan, sending a push — is written as an
 * `OutboxEvent` row inside the same transaction as the change that caused it. This
 * loop drains them. That is what makes the effects reliable without a broker: a
 * committed expense can never lose its notification, and a failed push can never
 * roll back an expense.
 *
 * Swapping this for Redis/BullMQ later is a change to this file alone; the rest of
 * the code just writes outbox rows.
 */

const POLL_INTERVAL_MS = 1_000;
const BATCH_SIZE = 10;
const MAX_ATTEMPTS = 5;

let timer: NodeJS.Timeout | undefined;
let running = false;
let draining = false;

export function startWorkers(): void {
  if (timer) return;
  running = true;
  logger.info('outbox worker started');
  timer = setInterval(() => void tick(), POLL_INTERVAL_MS);
  timer.unref();
}

export async function stopWorkers(): Promise<void> {
  running = false;
  if (timer) clearInterval(timer);
  timer = undefined;
  // Let a batch in flight finish rather than killing it mid-transaction.
  for (let waited = 0; draining && waited < 10_000; waited += 100) {
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  logger.info('outbox worker stopped');
}

export async function tick(): Promise<number> {
  if (draining || !running) return 0;
  draining = true;

  try {
    const events = await prisma.outboxEvent.findMany({
      where: { processedAt: null, availableAt: { lte: new Date() }, attempts: { lt: MAX_ATTEMPTS } },
      orderBy: { availableAt: 'asc' },
      take: BATCH_SIZE
    });

    let handled = 0;
    for (const event of events) {
      // Claim it first: two workers polling the same table must not both run it.
      const claimed = await prisma.outboxEvent.updateMany({
        where: { id: event.id, processedAt: null },
        data: { attempts: { increment: 1 } }
      });
      if (claimed.count === 0) continue;

      try {
        await handle(event.topic, event.payload as Record<string, unknown>);
        await prisma.outboxEvent.update({
          where: { id: event.id },
          data: { processedAt: new Date(), lastError: null }
        });
        handled += 1;
      } catch (error) {
        const attempts = event.attempts + 1;
        const giveUp = attempts >= MAX_ATTEMPTS;
        await prisma.outboxEvent.update({
          where: { id: event.id },
          data: {
            lastError: (error as Error).message.slice(0, 500),
            // Exponential backoff: 2s, 8s, 18s, 32s.
            ...(giveUp ? {} : { availableAt: new Date(Date.now() + attempts * attempts * 2_000) })
          }
        });
        logger[giveUp ? 'error' : 'warn'](
          { topic: event.topic, attempts, err: error },
          giveUp ? 'outbox event abandoned' : 'outbox event failed, will retry'
        );
      }
    }
    return handled;
  } catch (error) {
    logger.error({ err: error }, 'outbox poll failed');
    return 0;
  } finally {
    draining = false;
  }
}

async function handle(topic: string, payload: Record<string, unknown>): Promise<void> {
  switch (topic) {
    case 'scan.process': {
      const scanId = payload.scanId;
      if (typeof scanId !== 'string') throw new Error('scan.process without a scanId');
      await processScan(scanId);
      return;
    }
    case 'notification.push': {
      const notificationId = payload.notificationId;
      if (typeof notificationId !== 'string') throw new Error('notification.push without an id');
      await deliverPush(notificationId);
      return;
    }
    default:
      logger.warn({ topic }, 'no handler for outbox topic');
  }
}

// Running this file directly is the `WORKERS=external` path.
if (process.argv[1]?.endsWith('workers/index.js') || process.argv[1]?.endsWith('workers/index.ts')) {
  startWorkers();
  process.on('SIGTERM', () => void stopWorkers().then(() => process.exit(0)));
  process.on('SIGINT', () => void stopWorkers().then(() => process.exit(0)));
}
