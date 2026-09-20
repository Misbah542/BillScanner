import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { prisma } from '../src/prisma-for-tests.js';
import { setOcrProvider, OcrError, type OcrProvider } from '../src/ocr/index.js';
import { app, auth, resetDatabase, seedCategories, signIn, tinyJpeg, type TestUser } from './helpers.js';

let me: TestUser;

beforeEach(async () => {
  await resetDatabase();
  await seedCategories();
  setOcrProvider(undefined); // back to the stub
  me = await signIn('scanner@example.com', 'Scanner');
});

afterAll(async () => {
  setOcrProvider(undefined);
  await prisma.$disconnect();
});

describe('the scan pipeline', () => {
  it('takes an upload and returns items the SERVER parsed', async () => {
    const response = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const scan = response.body.scan;
    expect(scan.status).toBe('SUCCEEDED');
    expect(scan.result.items).toHaveLength(4);
    expect(scan.result.items[0].name).toBe('Aerated Beverages');
    expect(scan.result.totals.totalMinor).toBe(257_500);
    expect(scan.result.merchantName).toBe('SOCIAL OFFLINE');
    expect(scan.confidence).toBeGreaterThan(0.8);
  });

  it('suggests a category alongside the items', async () => {
    const response = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const suggestions = response.body.scan.result.suggestions;
    expect(suggestions[0].slug).toBe('restaurant');
    expect(suggestions[0].reasons.length).toBeGreaterThan(0);
  });

  it('is pollable while it works', async () => {
    const created = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const polled = await request(app)
      .get(`/v1/scans/${created.body.scan.id}`)
      .set(auth(me))
      .expect(200);
    expect(polled.body.scan.status).toBe('SUCCEEDED');
    expect(polled.body.scan.result.totals.totalMinor).toBe(257_500);
  });

  it('does not pay for OCR twice on the same photo', async () => {
    const first = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const second = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill-again.jpg', contentType: 'image/jpeg' })
      .expect(200);

    expect(second.body.deduplicated).toBe(true);
    expect(second.body.scan.id).toBe(first.body.scan.id);
    expect(await prisma.scan.count({ where: { userId: me.id } })).toBe(1);
  });

  it('honours an Idempotency-Key so a retried upload is not a second scan', async () => {
    const first = await request(app)
      .post('/v1/scans')
      .set({ ...auth(me), 'Idempotency-Key': 'retry-key-1' })
      .attach('image', tinyJpeg, { filename: 'a.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const retry = await request(app)
      .post('/v1/scans')
      .set({ ...auth(me), 'Idempotency-Key': 'retry-key-1' })
      .attach('image', Buffer.concat([tinyJpeg, Buffer.from([0])]), {
        filename: 'a.jpg',
        contentType: 'image/jpeg'
      })
      .expect(200);

    expect(retry.body.scan.id).toBe(first.body.scan.id);
  });

  it('rejects a file type it cannot read', async () => {
    const response = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', Buffer.from('not an image'), {
        filename: 'notes.txt',
        contentType: 'text/plain'
      })
      .expect(400);
    expect(response.body.error.code).toBe('UNSUPPORTED_MEDIA');
  });

  it('asks for the file if none was attached', async () => {
    const response = await request(app).post('/v1/scans').set(auth(me)).expect(400);
    expect(response.body.error.code).toBe('NO_IMAGE');
  });

  it('will not show one user another user’s scan', async () => {
    const created = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const other = await signIn('nosy@example.com');
    await request(app).get(`/v1/scans/${created.body.scan.id}`).set(auth(other)).expect(404);
    await request(app).get(`/v1/scans/${created.body.scan.id}/image`).set(auth(other)).expect(404);
  });

  it('serves the receipt image back to its owner', async () => {
    const created = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const image = await request(app)
      .get(`/v1/scans/${created.body.scan.id}/image`)
      .set(auth(me))
      .expect(200);
    expect(image.headers['content-type']).toContain('image/jpeg');
    expect(image.body.length).toBe(tinyJpeg.length);
  });
});

describe('when the provider fails', () => {
  it('records a permanent failure with a reason the app can show', async () => {
    const failing: OcrProvider = {
      name: 'failing',
      async recognize() {
        throw new OcrError('NO_TEXT_FOUND', 'No text could be read from that photo.');
      }
    };
    setOcrProvider(failing);

    const response = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'blurry.jpg', contentType: 'image/jpeg' })
      .expect(202);

    expect(response.body.scan.status).toBe('FAILED');
    expect(response.body.scan.error.code).toBe('NO_TEXT_FOUND');
    expect(response.body.scan.result).toBeNull();
  });

  it('keeps a retryable failure queued rather than giving up', async () => {
    setOcrProvider({
      name: 'flaky',
      async recognize() {
        throw new OcrError('PROVIDER_UNAVAILABLE', 'Vision returned 503.', true);
      }
    });

    const response = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    expect(response.body.scan.status).toBe('QUEUED');
    const requeued = await prisma.outboxEvent.count({
      where: { topic: 'scan.process', processedAt: null }
    });
    expect(requeued).toBeGreaterThanOrEqual(1);
  });

  it('lets the user ask for another go, and succeeds once the provider recovers', async () => {
    setOcrProvider({
      name: 'failing',
      async recognize() {
        throw new OcrError('PROVIDER_REJECTED', 'nope');
      }
    });
    const failed = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);
    expect(failed.body.scan.status).toBe('FAILED');

    setOcrProvider(undefined);
    const retried = await request(app)
      .post(`/v1/scans/${failed.body.scan.id}/retry`)
      .set(auth(me))
      .expect(202);
    expect(retried.body.scan.status).toBe('SUCCEEDED');
    expect(retried.body.scan.result.items).toHaveLength(4);
  });

  it('refuses to re-run a scan that already worked', async () => {
    const created = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const response = await request(app)
      .post(`/v1/scans/${created.body.scan.id}/retry`)
      .set(auth(me))
      .expect(409);
    expect(response.body.error.code).toBe('ALREADY_DONE');
  });
});

describe('scan to expense', () => {
  it('turns a scan result into a split bill in one call', async () => {
    const aditi = await signIn('a2@example.com', 'Aditi');
    const scan = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const result = scan.body.scan.result;
    const expense = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        source: 'SCAN',
        scanId: scan.body.scan.id,
        merchantName: result.merchantName,
        itemTotalMinor: result.totals.itemTotalMinor,
        serviceChargeMinor: result.totals.serviceChargeMinor,
        taxMinor: result.totals.taxMinor,
        roundOffMinor: result.totals.roundOffMinor,
        totalMinor: result.totals.totalMinor,
        items: result.items.map((item: Record<string, number | string>) => ({
          name: item.name,
          quantityMilli: item.quantityMilli,
          unitPriceMinor: item.unitPriceMinor,
          amountMinor: item.amountMinor
        })),
        taxLines: result.taxLines,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    expect(expense.body.expense.items).toHaveLength(4);
    expect(expense.body.expense.taxLines).toHaveLength(3);
    expect(expense.body.expense.totals.totalMinor).toBe(257_500);
    expect(
      expense.body.expense.shares.reduce((a: number, s: { amountMinor: number }) => a + s.amountMinor, 0)
    ).toBe(257_500);

    const linked = await request(app)
      .get(`/v1/scans/${scan.body.scan.id}`)
      .set(auth(me))
      .expect(200);
    expect(linked.body.scan.expenseId).toBe(expense.body.expense.id);
  });
});
