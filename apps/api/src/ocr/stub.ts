import type { OcrProvider, OcrResult } from './provider.js';
import { OcrError } from './provider.js';

/**
 * The provider a fresh checkout and CI use: no credentials, no network, no cost.
 *
 * It returns a plausible Indian restaurant receipt so the whole pipeline —
 * upload, queue, worker, shared parser, category suggestion, split — can be
 * exercised end to end. Swap `OCR_PROVIDER` to a real one for anything real.
 */
const SAMPLE = `SOCIAL OFFLINE
Linking Road, Bandra West, Mumbai
GSTIN: 27AABCU9603R1ZX
Invoice No: SO/2026/4471
Date: 19-09-2026

Aerated Beverages    2    120.00    240.00
Chilli Cheese Toast  1    345.00    345.00
LIIT Pitcher         2    650.00    1,300.00
Butter Chicken       1    400.00    400.00

Sub Total                          2,285.00
Service Charge 10%                   228.50
CGST 2.5%                             30.84
SGST 2.5%                             30.84
Round Off                             -0.18
Net Amount                         2,575.00

Thank you, visit again`;

export class StubOcrProvider implements OcrProvider {
  readonly name = 'stub';

  async recognize(image: Buffer, mimeType: string): Promise<OcrResult> {
    if (image.byteLength === 0) {
      throw new OcrError('PROVIDER_REJECTED', 'The uploaded image was empty.');
    }
    if (!mimeType.startsWith('image/') && mimeType !== 'application/pdf') {
      throw new OcrError('PROVIDER_REJECTED', `The stub provider cannot read ${mimeType}.`);
    }
    return { text: SAMPLE, confidence: 0.92, raw: { provider: 'stub', bytes: image.byteLength } };
  }
}
