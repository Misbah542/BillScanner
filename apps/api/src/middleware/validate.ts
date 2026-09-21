import type { RequestHandler } from 'express';
import type { ZodTypeAny, z } from 'zod';

/**
 * Parses and REPLACES the input with the parsed value, so handlers downstream
 * work with coerced, defaulted, trimmed data and never with `req.body as any`.
 */
export function validateBody<S extends ZodTypeAny>(schema: S): RequestHandler {
  return (req, _res, next) => {
    const result = schema.safeParse(req.body);
    if (!result.success) return next(result.error);
    req.body = result.data;
    next();
  };
}

export function validateQuery<S extends ZodTypeAny>(schema: S): RequestHandler {
  return (req, _res, next) => {
    const result = schema.safeParse(req.query);
    if (!result.success) return next(result.error);
    // Express 4 lets us reassign req.query; keep the parsed object.
    Object.defineProperty(req, 'query', { value: result.data, writable: true, configurable: true });
    next();
  };
}

export function validateParams<S extends ZodTypeAny>(schema: S): RequestHandler {
  return (req, _res, next) => {
    const result = schema.safeParse(req.params);
    if (!result.success) return next(result.error);
    Object.defineProperty(req, 'params', { value: result.data, writable: true, configurable: true });
    next();
  };
}

export type Parsed<S extends ZodTypeAny> = z.infer<S>;
