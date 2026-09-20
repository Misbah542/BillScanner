import { env } from '../env.js';
import type { OcrProvider, OcrResult } from './provider.js';
import { OcrError } from './provider.js';

interface VisionResponse {
  responses?: Array<{
    fullTextAnnotation?: { text?: string };
    textAnnotations?: Array<{ description?: string }>;
    error?: { message?: string };
  }>;
}

/**
 * Google Cloud Vision `DOCUMENT_TEXT_DETECTION`, which keeps the line structure a
 * receipt depends on far better than plain `TEXT_DETECTION`.
 */
export class GoogleVisionProvider implements OcrProvider {
  readonly name = 'google-vision';

  constructor() {
    if (!env.GOOGLE_VISION_API_KEY) {
      throw new OcrError(
        'MISCONFIGURED',
        'OCR_PROVIDER=google-vision needs GOOGLE_VISION_API_KEY to be set.'
      );
    }
  }

  async recognize(image: Buffer, _mimeType: string): Promise<OcrResult> {
    const response = await fetch(
      `https://vision.googleapis.com/v1/images:annotate?key=${env.GOOGLE_VISION_API_KEY}`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          requests: [
            {
              image: { content: image.toString('base64') },
              features: [{ type: 'DOCUMENT_TEXT_DETECTION' }],
              imageContext: { languageHints: ['en', 'hi'] }
            }
          ]
        })
      }
    ).catch((error: unknown) => {
      throw new OcrError('PROVIDER_UNAVAILABLE', `Could not reach Vision: ${String(error)}`, true);
    });

    if (!response.ok) {
      // 429 and 5xx are worth another go; a 400 means the image itself is wrong.
      const retryable = response.status === 429 || response.status >= 500;
      throw new OcrError(
        retryable ? 'PROVIDER_UNAVAILABLE' : 'PROVIDER_REJECTED',
        `Vision returned ${response.status}.`,
        retryable
      );
    }

    const body = (await response.json()) as VisionResponse;
    const first = body.responses?.[0];
    if (first?.error?.message) {
      throw new OcrError('PROVIDER_REJECTED', first.error.message);
    }

    const text = first?.fullTextAnnotation?.text ?? first?.textAnnotations?.[0]?.description ?? '';
    if (!text.trim()) {
      throw new OcrError('NO_TEXT_FOUND', 'No text could be read from that photo.');
    }
    return { text, raw: body };
  }
}
