/**
 * Receipt recognition sits behind this interface on purpose.
 *
 * The client uploads an image and gets structured items back; it never runs OCR
 * itself. That keeps the recognition swappable (stub in development and CI,
 * Google Vision or Textract in production), lets the parsing rules be fixed for
 * every user at once without an app release, and means a cheap phone is not doing
 * the work. It is also the only way the pipeline scales horizontally: uploads land
 * in storage, workers pull them off a queue.
 */
export interface OcrResult {
  /** Plain text, one receipt line per newline. The shared parser turns this into items. */
  text: string;
  /** The provider's own confidence, if it reports one. */
  confidence?: number;
  /** Kept on the Scan row for debugging a bad read. */
  raw?: unknown;
}

export interface OcrProvider {
  readonly name: string;
  recognize(image: Buffer, mimeType: string): Promise<OcrResult>;
}

export class OcrError extends Error {
  constructor(
    readonly code: 'PROVIDER_UNAVAILABLE' | 'PROVIDER_REJECTED' | 'NO_TEXT_FOUND' | 'MISCONFIGURED',
    message: string,
    readonly retryable = false
  ) {
    super(message);
    this.name = 'OcrError';
  }
}
