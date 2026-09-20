import { env } from '../env.js';
import { logger } from '../lib/logger.js';
import { GoogleVisionProvider } from './googleVision.js';
import type { OcrProvider } from './provider.js';
import { OcrError } from './provider.js';
import { StubOcrProvider } from './stub.js';

export * from './provider.js';

let cached: OcrProvider | undefined;

export function ocrProvider(): OcrProvider {
  if (cached) return cached;

  switch (env.OCR_PROVIDER) {
    case 'google-vision':
      cached = new GoogleVisionProvider();
      break;
    case 'textract':
      throw new OcrError(
        'MISCONFIGURED',
        'The Textract provider is not implemented yet — add src/ocr/textract.ts and register it here.'
      );
    default:
      cached = new StubOcrProvider();
  }

  logger.info({ provider: cached.name }, 'ocr provider ready');
  return cached;
}

/** Test seam: lets a test install a fake provider without touching env. */
export function setOcrProvider(provider: OcrProvider | undefined): void {
  cached = provider;
}
