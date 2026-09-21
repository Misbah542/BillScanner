import type { Express } from 'express';
import request from 'supertest';
import { CATEGORIES } from '@snaptab/shared';
import { prisma } from '../src/prisma-for-tests.js';
import { createServer } from '../src/server.js';

export const app: Express = createServer();

/**
 * Empties every table between tests. One TRUNCATE ... CASCADE is far faster than
 * deleting in dependency order, and cannot get the order wrong.
 */
export async function resetDatabase(): Promise<void> {
  const tables = await prisma.$queryRaw<Array<{ tablename: string }>>`
    SELECT tablename FROM pg_tables
    WHERE schemaname = 'public' AND tablename NOT LIKE '_prisma%'
  `;
  if (tables.length === 0) return;
  const list = tables.map((row) => `"public"."${row.tablename}"`).join(', ');
  await prisma.$executeRawUnsafe(`TRUNCATE TABLE ${list} CASCADE`);
}

export async function seedCategories(): Promise<void> {
  await prisma.category.createMany({
    data: CATEGORIES.map((category, index) => ({
      slug: category.slug,
      name: category.name,
      iconKey: category.icon,
      colorHex: category.color,
      tintHex: category.tintLight,
      kind: category.kind,
      sortOrder: index
    })),
    skipDuplicates: true
  });
}

export interface TestUser {
  id: string;
  accessToken: string;
  refreshToken: string;
  email: string;
  name: string | null;
}

/** Signs in for real, through the OTP endpoints, rather than forging a token. */
export async function signIn(email: string, name?: string): Promise<TestUser> {
  const start = await request(app).post('/v1/auth/otp/start').send({ contact: email }).expect(202);
  const code: string = start.body.devCode;

  const verify = await request(app)
    .post('/v1/auth/otp/verify')
    .send({ contact: email, code, ...(name ? { name } : {}) })
    .expect(200);

  return {
    id: verify.body.user.id,
    accessToken: verify.body.accessToken,
    refreshToken: verify.body.refreshToken,
    email: email.toLowerCase(),
    name: verify.body.user.name
  };
}

export function auth(user: TestUser): Record<string, string> {
  return { Authorization: `Bearer ${user.accessToken}` };
}

/** A minimal 1×1 JPEG, enough for the stub OCR provider to accept an upload. */
export const tinyJpeg = Buffer.from(
  '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwcJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDc0NDP/wAALCAABAAEBAREA/8QAFAABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AVwA//9k=',
  'base64'
);
