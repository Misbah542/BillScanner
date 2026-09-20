/**
 * Every failure the API reports on purpose is an ApiError. The handler in
 * middleware/error.ts turns it into a stable JSON envelope; anything that is not
 * an ApiError is a bug and becomes a 500 with no detail leaked.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown
  ) {
    super(message);
    this.name = 'ApiError';
  }

  static badRequest(code: string, message: string, details?: unknown): ApiError {
    return new ApiError(400, code, message, details);
  }

  static unauthorized(message = 'Sign in to continue.', code = 'UNAUTHENTICATED'): ApiError {
    return new ApiError(401, code, message);
  }

  static forbidden(message = 'You do not have access to this.', code = 'FORBIDDEN'): ApiError {
    return new ApiError(403, code, message);
  }

  static notFound(what = 'That'): ApiError {
    return new ApiError(404, 'NOT_FOUND', `${what} could not be found.`);
  }

  static conflict(code: string, message: string, details?: unknown): ApiError {
    return new ApiError(409, code, message, details);
  }

  static tooLarge(message: string): ApiError {
    return new ApiError(413, 'PAYLOAD_TOO_LARGE', message);
  }

  static unprocessable(code: string, message: string, details?: unknown): ApiError {
    return new ApiError(422, code, message, details);
  }

  static tooManyRequests(message = 'Too many attempts. Try again shortly.'): ApiError {
    return new ApiError(429, 'RATE_LIMITED', message);
  }

  static notImplemented(code: string, message: string): ApiError {
    return new ApiError(501, code, message);
  }
}
