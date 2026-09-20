import pino from 'pino';
import { env, isProduction, isTest } from '../env.js';

export const logger = pino({
  level: env.LOG_LEVEL,
  // Pretty output only in development. Production wants newline-delimited JSON for
  // its log pipeline, and tests want silence with no extra transport process.
  ...(isProduction || isTest
    ? {}
    : { transport: { target: 'pino-pretty', options: { colorize: true } } }),
  /**
   * Anything that could identify a person or move money is redacted. The
   * original BillScanner logged whole request and response bodies at BODY level
   * in every build; this is the opposite of that.
   */
  redact: {
    paths: [
      'req.headers.authorization',
      'req.headers.cookie',
      'req.body.code',
      'req.body.idToken',
      'req.body.refreshToken',
      'req.body.rawBody',
      'req.body.email',
      'req.body.phone',
      'res.headers["set-cookie"]',
      'password',
      'codeHash',
      'refreshToken',
      'accessToken'
    ],
    censor: '[redacted]'
  }
});
