import { randomUUID } from 'node:crypto';
import cors from 'cors';
import express, { type Express } from 'express';
import helmet from 'helmet';
import { pinoHttp } from 'pino-http';
import { env, isTest } from './env.js';
import { logger } from './lib/logger.js';
import { prisma } from './lib/prisma.js';
import { errorHandler, notFoundHandler } from './middleware/error.js';
import { apiRouter, publicShareRouter } from './modules/index.js';

export function createServer(): Express {
  const app = express();

  app.disable('x-powered-by');
  // Behind a load balancer, so req.ip is the client rather than the proxy.
  app.set('trust proxy', 1);

  app.use(
    helmet({
      // The API serves JSON and images, never HTML with inline script.
      contentSecurityPolicy: { directives: { defaultSrc: ["'none'"], imgSrc: ["'self'", 'data:'] } },
      crossOriginResourcePolicy: { policy: 'cross-origin' }
    })
  );

  app.use(
    cors({
      origin: true,
      credentials: false,
      // Idempotency-Key is used by the scan upload.
      allowedHeaders: ['authorization', 'content-type', 'idempotency-key'],
      exposedHeaders: ['x-request-id']
    })
  );

  app.use((req, res, next) => {
    const id = (req.headers['x-request-id'] as string | undefined) ?? randomUUID();
    res.setHeader('x-request-id', id);
    next();
  });

  if (!isTest) {
    app.use(
      pinoHttp({
        logger,
        genReqId: (_req, res) => String(res.getHeader('x-request-id')),
        customLogLevel: (_req, res, error) => {
          if (error || res.statusCode >= 500) return 'error';
          if (res.statusCode >= 400) return 'warn';
          return 'info';
        }
      })
    );
  }

  app.use(express.json({ limit: '1mb' }));
  app.use(express.urlencoded({ extended: false, limit: '1mb' }));

  /** Liveness. Deliberately does not touch the database. */
  app.get('/healthz', (_req, res) => {
    res.json({ ok: true, service: 'snaptab-api', uptimeSeconds: Math.round(process.uptime()) });
  });

  /** Readiness. Fails when Postgres is unreachable, so a rollout waits. */
  app.get('/readyz', async (_req, res) => {
    try {
      await prisma.$queryRaw`SELECT 1`;
      res.json({ ok: true });
    } catch (error) {
      logger.error({ err: error }, 'readiness check failed');
      res.status(503).json({ ok: false, error: 'database unavailable' });
    }
  });

  app.use('/v1', apiRouter);
  // The public share view sits outside /v1 and outside auth: it is the URL people
  // paste into a group chat.
  app.use('/t', publicShareRouter);

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}
