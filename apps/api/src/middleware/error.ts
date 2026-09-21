import type { ErrorRequestHandler, RequestHandler } from 'express';
import { ZodError } from 'zod';
import { SplitError } from '@snaptab/shared';
import { ApiError } from '../lib/errors.js';
import { logger } from '../lib/logger.js';
import { uniqueViolationTarget } from '../lib/prisma.js';
import { isProduction } from '../env.js';

export const notFoundHandler: RequestHandler = (req, _res, next) => {
  next(new ApiError(404, 'ROUTE_NOT_FOUND', `${req.method} ${req.path} is not a route.`));
};

/**
 * One shape for every error the API returns:
 *   { error: { code, message, details? } }
 *
 * `message` is written for a person to read — the Android app shows it directly,
 * which is why split failures carry their arithmetic in `details`.
 */
export const errorHandler: ErrorRequestHandler = (error, req, res, _next) => {
  const requestId = res.getHeader('x-request-id');

  if (error instanceof ApiError) {
    res.status(error.status).json({
      error: { code: error.code, message: error.message, ...(error.details ? { details: error.details } : {}) }
    });
    return;
  }

  if (error instanceof ZodError) {
    res.status(422).json({
      error: {
        code: 'VALIDATION_FAILED',
        message: 'Some of that could not be accepted.',
        details: error.issues.map((issue) => ({
          path: issue.path.join('.'),
          message: issue.message
        }))
      }
    });
    return;
  }

  // The split engine's failures are user-facing: "the percentages add up to 90%".
  if (error instanceof SplitError) {
    res.status(422).json({
      error: { code: `SPLIT_${error.code}`, message: error.message, ...(error.detail ? { details: error.detail } : {}) }
    });
    return;
  }

  const conflict = uniqueViolationTarget(error);
  if (conflict) {
    res.status(409).json({
      error: {
        code: 'ALREADY_EXISTS',
        message: 'Something with those details already exists.',
        details: { fields: conflict }
      }
    });
    return;
  }

  if ((error as { type?: string }).type === 'entity.too.large') {
    res.status(413).json({ error: { code: 'PAYLOAD_TOO_LARGE', message: 'That request was too large.' } });
    return;
  }

  logger.error({ err: error, requestId, path: req.path, method: req.method }, 'unhandled error');
  res.status(500).json({
    error: {
      code: 'INTERNAL',
      message: 'Something went wrong on our side.',
      ...(isProduction ? {} : { details: { reason: (error as Error)?.message } })
    }
  });
};
