import rateLimit from 'express-rate-limit';
import { isTest } from '../env.js';
import { ApiError } from '../lib/errors.js';

function limiter(options: { windowMs: number; max: number; message: string }) {
  return rateLimit({
    windowMs: options.windowMs,
    // Tests would otherwise trip the limiter and fail intermittently.
    limit: isTest ? 1_000_000 : options.max,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    handler: (_req, _res, next) => next(ApiError.tooManyRequests(options.message)),
    // Rate limit per account once authenticated, per IP before that.
    keyGenerator: (req) => req.user?.id ?? req.ip ?? 'unknown'
  });
}

/** Asking for a code texts or emails someone. Tight. */
export const otpRequestLimiter = limiter({
  windowMs: 15 * 60 * 1000,
  max: 5,
  message: 'Too many codes requested. Wait a few minutes and try again.'
});

/** Guessing a six-digit code. Tighter still, and the challenge also counts attempts. */
export const otpVerifyLimiter = limiter({
  windowMs: 15 * 60 * 1000,
  max: 10,
  message: 'Too many wrong codes. Wait a few minutes and try again.'
});

/** Uploads are expensive: storage plus an OCR call. */
export const scanLimiter = limiter({
  windowMs: 60 * 60 * 1000,
  max: 120,
  message: 'That is a lot of scanning. Try again in a little while.'
});

export const writeLimiter = limiter({
  windowMs: 60 * 1000,
  max: 240,
  message: 'Slow down a moment.'
});

export const publicLimiter = limiter({
  windowMs: 60 * 1000,
  max: 60,
  message: 'Too many requests.'
});
