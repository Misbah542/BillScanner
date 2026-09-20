import { PrismaClient } from '@prisma/client';
import { env, isProduction } from '../env.js';
import { logger } from './logger.js';

const globalForPrisma = globalThis as unknown as { prisma?: PrismaClient };

/**
 * One client for the process. Held on globalThis so `tsx watch` reloading a
 * module does not open a new pool on every save.
 */
export const prisma: PrismaClient =
  globalForPrisma.prisma ??
  new PrismaClient({
    datasources: { db: { url: env.DATABASE_URL } },
    log: isProduction
      ? [{ emit: 'event', level: 'warn' }, { emit: 'event', level: 'error' }]
      : [{ emit: 'event', level: 'warn' }, { emit: 'event', level: 'error' }]
  });

prisma.$on('warn' as never, (event: unknown) => logger.warn({ prisma: event }, 'prisma warning'));
prisma.$on('error' as never, (event: unknown) => logger.error({ prisma: event }, 'prisma error'));

if (!isProduction) globalForPrisma.prisma = prisma;

/** Prisma's unique-constraint error, in a form callers can branch on. */
export function uniqueViolationTarget(error: unknown): string[] | null {
  const candidate = error as { code?: string; meta?: { target?: unknown } };
  if (candidate?.code !== 'P2002') return null;
  const target = candidate.meta?.target;
  if (Array.isArray(target)) return target.map(String);
  if (typeof target === 'string') return [target];
  return [];
}
