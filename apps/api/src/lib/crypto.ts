import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';

/** URL-safe random token for refresh tokens, invites and share links. */
export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

/** A six-digit OTP, uniformly distributed — not `Math.random()`. */
export function randomOtp(digits = 6): string {
  const max = 10 ** digits;
  return String(randomInt(0, max)).padStart(digits, '0');
}

/**
 * Refresh tokens, OTPs and share tokens are stored as SHA-256. They are already
 * high-entropy random values, so a slow KDF buys nothing here — unlike a
 * password, which would need argon2.
 */
export function sha256(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

/** Constant-time compare of two hex digests. */
export function safeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  try {
    return timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'));
  } catch {
    return false;
  }
}

/** IPs are kept only as a salted hash, for rate limiting and session forensics. */
export function hashIp(ip: string | undefined, salt: string): string | undefined {
  if (!ip) return undefined;
  return createHash('sha256').update(`${salt}:${ip}`).digest('hex').slice(0, 32);
}

export function sha256Buffer(buffer: Buffer): string {
  return createHash('sha256').update(buffer).digest('hex');
}
