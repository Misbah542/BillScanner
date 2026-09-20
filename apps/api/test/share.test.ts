import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { parseBankAlert } from '@snaptab/shared';
import { prisma } from '../src/prisma-for-tests.js';
import { app, auth, resetDatabase, seedCategories, signIn, tinyJpeg, type TestUser } from './helpers.js';

let me: TestUser;
let aditi: TestUser;

beforeEach(async () => {
  await resetDatabase();
  await seedCategories();
  me = await signIn('sharer@example.com', 'Sharer');
  aditi = await signIn('sharee@example.com', 'Aditi');
});

afterAll(async () => {
  await prisma.$disconnect();
});

async function sharedBill() {
  const response = await request(app)
    .post('/v1/expenses')
    .set(auth(me))
    .send({
      kind: 'SHARED',
      merchantName: 'Social Offline',
      itemTotalMinor: 100_000,
      totalMinor: 100_000,
      items: [{ name: 'Pitcher', quantityMilli: 1000, unitPriceMinor: 100_000, amountMinor: 100_000 }],
      splitMethod: 'EQUAL',
      participants: [{ userId: me.id }, { userId: aditi.id }]
    })
    .expect(201);
  return response.body.expense;
}

describe('sharing a tab by link', () => {
  it('gives a link anyone can open with no account', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);

    expect(created.body.link.url).toContain(created.body.link.token);

    // No Authorization header at all.
    const viewed = await request(app).get(`/t/${created.body.link.token}`).expect(200);
    expect(viewed.body.kind).toBe('EXPENSE');
    expect(viewed.body.expense.merchantName).toBe('Social Offline');
    expect(viewed.body.expense.totals.totalMinor).toBe(100_000);
    expect(viewed.body.expense.shares).toHaveLength(2);
    expect(viewed.body.expense.items).toHaveLength(1);
  });

  it('shows names and amounts but no contact details or user ids', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);

    const body = JSON.stringify((await request(app).get(`/t/${created.body.link.token}`).expect(200)).body);

    expect(body).toContain('Aditi');
    expect(body).not.toContain('sharee@example.com');
    expect(body).not.toContain('sharer@example.com');
    expect(body).not.toContain(aditi.id);
    expect(body).not.toContain(me.id);
  });

  it('never exposes the bank alert behind an expense', async () => {
    const expense = await sharedBill();
    const parsed = parseBankAlert(
      'Sent Rs.1000.00 from HDFC Bank A/C x4471 to SOCIAL OFFLINE on 20-09-26. UPI Ref 5281130944',
      { sender: 'VM-HDFCBK', receivedAt: new Date() }
    )!;
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({
        alerts: [
          {
            direction: parsed.direction,
            amountMinor: parsed.amountMinor,
            merchantRaw: parsed.merchantRaw,
            accountMask: parsed.accountMask,
            occurredAt: new Date().toISOString(),
            fingerprint: parsed.fingerprint
          }
        ]
      })
      .expect(201);
    await request(app)
      .post(`/v1/alerts/${ingest.body.results[0].alertId}/link`)
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(200);

    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);
    const body = JSON.stringify((await request(app).get(`/t/${created.body.link.token}`).expect(200)).body);

    expect(body).not.toContain('4471');
    expect(body).not.toContain('matchedAlerts');
  });

  it('withholds the receipt on a SUMMARY link and serves it on a FULL one', async () => {
    const scan = await request(app)
      .post('/v1/scans')
      .set(auth(me))
      .attach('image', tinyJpeg, { filename: 'bill.jpg', contentType: 'image/jpeg' })
      .expect(202);

    const expense = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Social Offline',
        totalMinor: 100_000,
        scanId: scan.body.scan.id,
        receiptAssetKey: (await prisma.scan.findUniqueOrThrow({ where: { id: scan.body.scan.id } })).assetKey,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const summary = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.body.expense.id, scope: 'SUMMARY' })
      .expect(201);
    const summaryView = await request(app).get(`/t/${summary.body.link.token}`).expect(200);
    expect(summaryView.body.expense.receiptUrl).toBeNull();
    await request(app).get(`/t/${summary.body.link.token}/receipt`).expect(404);

    const full = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.body.expense.id, scope: 'FULL' })
      .expect(201);
    const fullView = await request(app).get(`/t/${full.body.link.token}`).expect(200);
    expect(fullView.body.expense.receiptUrl).toContain(full.body.link.token);
    await request(app).get(`/t/${full.body.link.token}/receipt`).expect(200);
  });

  it('stops working once revoked', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);

    await request(app).get(`/t/${created.body.link.token}`).expect(200);
    await request(app).delete(`/v1/share-links/${created.body.link.id}`).set(auth(me)).expect(204);
    await request(app).get(`/t/${created.body.link.token}`).expect(404);
  });

  it('expires when told to', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id, expiresInDays: 7 })
      .expect(201);

    await prisma.shareLink.update({
      where: { id: created.body.link.id },
      data: { expiresAt: new Date(Date.now() - 1000) }
    });

    const response = await request(app).get(`/t/${created.body.link.token}`).expect(410);
    expect(response.body.error.code).toBe('LINK_EXPIRED');
  });

  it('counts views', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);

    await request(app).get(`/t/${created.body.link.token}`).expect(200);
    await request(app).get(`/t/${created.body.link.token}`).expect(200);

    const row = await prisma.shareLink.findUniqueOrThrow({ where: { id: created.body.link.id } });
    expect(row.viewCount).toBe(2);
  });

  it('will not let a stranger make a link for someone else’s tab', async () => {
    const expense = await sharedBill();
    const stranger = await signIn('nope@example.com');
    await request(app)
      .post('/v1/share-links')
      .set(auth(stranger))
      .send({ expenseId: expense.id })
      .expect(403);
  });

  it('refuses a link for both an expense and a group at once', async () => {
    const expense = await sharedBill();
    const group = await request(app)
      .post('/v1/groups')
      .set(auth(me))
      .send({ name: 'Trip' })
      .expect(201);
    await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id, groupId: group.body.group.id })
      .expect(422);
  });

  it('404s on a token that was never issued', async () => {
    await request(app).get('/t/totally-made-up-token').expect(404);
  });

  it('hides a deleted expense behind a live link', async () => {
    const expense = await sharedBill();
    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ expenseId: expense.id })
      .expect(201);

    await request(app).delete(`/v1/expenses/${expense.id}`).set(auth(me)).expect(204);
    await request(app).get(`/t/${created.body.link.token}`).expect(404);
  });
});

describe('sharing a group', () => {
  it('shows the group’s expenses and member names only', async () => {
    const group = await request(app)
      .post('/v1/groups')
      .set(auth(me))
      .send({ name: 'Goa trip', invite: ['sharee@example.com'] })
      .expect(201);

    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        groupId: group.body.group.id,
        merchantName: 'Hotel',
        totalMinor: 400_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const created = await request(app)
      .post('/v1/share-links')
      .set(auth(me))
      .send({ groupId: group.body.group.id })
      .expect(201);

    const view = await request(app).get(`/t/${created.body.link.token}`).expect(200);
    expect(view.body.kind).toBe('GROUP');
    expect(view.body.group.name).toBe('Goa trip');
    expect(view.body.group.memberNames).toContain('Aditi');
    expect(view.body.group.expenses).toHaveLength(1);
    expect(JSON.stringify(view.body)).not.toContain('sharee@example.com');
  });
});

describe('service basics', () => {
  it('answers a health check without touching the database', async () => {
    const response = await request(app).get('/healthz').expect(200);
    expect(response.body.ok).toBe(true);
  });

  it('answers a readiness check by touching the database', async () => {
    const response = await request(app).get('/readyz').expect(200);
    expect(response.body.ok).toBe(true);
  });

  it('tells the app what the server supports', async () => {
    const response = await request(app).get('/v1/config').expect(200);
    expect(response.body.auth.email).toBe(true);
    expect(response.body.auth.phone).toBe(true);
    expect(response.body.auth.google).toBe(false);
    expect(response.body.rules.sms).toBeGreaterThanOrEqual(1);
  });

  it('returns a structured 404 for an unknown route', async () => {
    const response = await request(app).get('/v1/nope').expect(404);
    expect(response.body.error.code).toBe('ROUTE_NOT_FOUND');
  });

  it('puts a request id on every response', async () => {
    const response = await request(app).get('/healthz').expect(200);
    expect(response.headers['x-request-id']).toBeTruthy();
  });
});
