import { createServer } from './server.js';
import { env } from './env.js';
import { logger } from './lib/logger.js';
import { prisma } from './lib/prisma.js';
import { startWorkers, stopWorkers } from './workers/index.js';

const app = createServer();
const server = app.listen(env.PORT, () => {
  logger.info(
    { port: env.PORT, env: env.NODE_ENV, ocr: env.OCR_PROVIDER, storage: env.STORAGE_DRIVER },
    'snaptab api listening'
  );
});

/**
 * Workers run in-process by default, which is all a single box needs. Set
 * `WORKERS=external` and run `npm run start:worker` separately to scale the scan
 * pipeline independently of the API.
 */
const runWorkersInProcess = process.env.WORKERS !== 'external';
if (runWorkersInProcess) startWorkers();

let shuttingDown = false;

async function shutdown(signal: string): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  logger.info({ signal }, 'shutting down');

  // Stop accepting connections, let in-flight requests finish, then let go of the
  // database. A scan mid-OCR is safe to drop: it stays QUEUED and gets picked up.
  server.close(async () => {
    if (runWorkersInProcess) await stopWorkers();
    await prisma.$disconnect();
    logger.info('shutdown complete');
    process.exit(0);
  });

  setTimeout(() => {
    logger.warn('forcing exit after 15s');
    process.exit(1);
  }, 15_000).unref();
}

process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('unhandledRejection', (reason) => {
  logger.error({ reason }, 'unhandled promise rejection');
});
