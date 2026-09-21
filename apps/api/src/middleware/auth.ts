import type { RequestHandler } from 'express';
import { ApiError } from '../lib/errors.js';
import { prisma } from '../lib/prisma.js';
import { verifyAccessToken } from '../lib/tokens.js';

export interface AuthedUser {
  id: string;
  status: string;
  sessionId: string;
  currency: string;
  timezone: string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      user?: AuthedUser;
    }
  }
}

function bearer(header: string | undefined): string | null {
  if (!header) return null;
  const [scheme, token] = header.split(' ');
  if (!scheme || scheme.toLowerCase() !== 'bearer' || !token) return null;
  return token.trim();
}

/**
 * Verifies the access token, then confirms the session behind it is still live.
 * The session lookup is what makes "sign out everywhere" actually work — without
 * it a stolen access token stays valid for its full TTL.
 */
export const requireAuth: RequestHandler = async (req, _res, next) => {
  try {
    const token = bearer(req.headers.authorization);
    if (!token) throw ApiError.unauthorized();

    const claims = await verifyAccessToken(token);

    const session = await prisma.session.findUnique({
      where: { id: claims.sid },
      select: {
        revokedAt: true,
        expiresAt: true,
        user: { select: { id: true, status: true, currency: true, timezone: true } }
      }
    });

    if (!session || session.revokedAt || session.expiresAt < new Date()) {
      throw ApiError.unauthorized('That session has ended. Sign in again.', 'SESSION_ENDED');
    }
    if (session.user.status === 'DISABLED') {
      throw ApiError.forbidden('This account is disabled.', 'ACCOUNT_DISABLED');
    }

    req.user = {
      id: session.user.id,
      status: session.user.status,
      sessionId: claims.sid,
      currency: session.user.currency,
      timezone: session.user.timezone
    };
    next();
  } catch (error) {
    next(error);
  }
};

/** For handlers that must not run for a placeholder who has never signed in. */
export const requireActiveUser: RequestHandler = (req, _res, next) => {
  if (req.user?.status !== 'ACTIVE') {
    return next(ApiError.forbidden('Finish setting up your account first.', 'ACCOUNT_NOT_ACTIVE'));
  }
  next();
};

export function currentUser(req: Express.Request): AuthedUser {
  if (!req.user) throw ApiError.unauthorized();
  return req.user;
}
