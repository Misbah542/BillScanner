import { SignJWT, jwtVerify } from 'jose';
import { env } from '../env.js';
import { ApiError } from './errors.js';

const secret = new TextEncoder().encode(env.JWT_SECRET);
const ISSUER = 'snaptab';
const AUDIENCE = 'snaptab-app';

export interface AccessTokenClaims {
  sub: string;
  sid: string;
  /** Present so a handler can refuse an invited placeholder where it matters. */
  status: string;
}

/**
 * Access tokens are short-lived and stateless — no database read per request.
 * Revocation happens on the refresh token, which is why the access TTL is
 * fifteen minutes by default rather than a day.
 */
export async function signAccessToken(claims: AccessTokenClaims): Promise<string> {
  return new SignJWT({ sid: claims.sid, status: claims.status })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(claims.sub)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${env.ACCESS_TOKEN_TTL}s`)
    .sign(secret);
}

export async function verifyAccessToken(token: string): Promise<AccessTokenClaims> {
  try {
    const { payload } = await jwtVerify(token, secret, { issuer: ISSUER, audience: AUDIENCE });
    if (!payload.sub || typeof payload.sid !== 'string') {
      throw ApiError.unauthorized('That session is not usable. Sign in again.');
    }
    return {
      sub: payload.sub,
      sid: payload.sid,
      status: typeof payload.status === 'string' ? payload.status : 'ACTIVE'
    };
  } catch (error) {
    if (error instanceof ApiError) throw error;
    const expired = (error as { code?: string }).code === 'ERR_JWT_EXPIRED';
    throw ApiError.unauthorized(
      expired ? 'Your session expired. Refreshing…' : 'That token is not valid.',
      expired ? 'TOKEN_EXPIRED' : 'TOKEN_INVALID'
    );
  }
}

/**
 * A signed token carrying only a share-link id, used for public read-only bill
 * views. Separate from access tokens so a leaked share link can never be
 * mistaken for a session.
 */
export async function signShareToken(shareLinkId: string, ttlSeconds: number): Promise<string> {
  return new SignJWT({ kind: 'share' })
    .setProtectedHeader({ alg: 'HS256', typ: 'JWT' })
    .setSubject(shareLinkId)
    .setIssuer(ISSUER)
    .setAudience('snaptab-share')
    .setIssuedAt()
    .setExpirationTime(`${ttlSeconds}s`)
    .sign(secret);
}
