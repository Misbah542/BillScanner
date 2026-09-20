import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { parseBankAlert } from '@snaptab/shared';
import { prisma } from '../src/prisma-for-tests.js';
import { app, auth, resetDatabase, seedCategories, signIn, type TestUser } from './helpers.js';

let me: TestUser;
let aditi: TestUser;

beforeEach(async () => {
  await resetDatabase();
  await seedCategories();
  me = await signIn('alerts@example.com', 'Me');
  aditi = await signIn('aditi2@example.com', 'Aditi');
});

afterAll(async () => {
  await prisma.$disconnect();
});

/** Mirrors what the Android receiver does: parse on the device, send fields only. */
function deviceParsed(body: string, sender = 'VM-HDFCBK') {
  const parsed = parseBankAlert(body, { sender, receivedAt: new Date('2026-09-20T13:12:00+05:30') });
  if (!parsed) throw new Error('the shared parser rejected that message');
  return {
    direction: parsed.direction,
    amountMinor: parsed.amountMinor,
    currency: parsed.currency,
    merchantRaw: parsed.merchantRaw,
    accountMask: parsed.accountMask,
    accountKind: parsed.accountKind,
    bankId: parsed.bankId,
    bankName: parsed.bankName,
    referenceNumber: parsed.referenceNumber,
    channel: 'SMS' as const,
    occurredAt: '2026-09-20T13:12:00+05:30',
    fingerprint: parsed.fingerprint,
    confidence: parsed.confidence
  };
}

const swiggy = 'Sent Rs.486.00 from HDFC Bank A/C x4471 to SWIGGY on 20-09-26. UPI Ref 528113094412';
const croma = 'INR 12,499.00 spent on ICICI Bank Card XX9082 on 19-Sep-26 at CROMA. Avl Lmt: INR 87,501.00';

describe('ingesting what the phone read', () => {
  it('files a debit as unmatched, with a suggested category', async () => {
    const response = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);

    expect(response.body.created).toBe(1);
    expect(response.body.results[0].status).toBe('UNMATCHED');
    expect(response.body.results[0].suggestedCategorySlug).toBe('restaurant');
  });

  it('never stores the message body unless the user opted in', async () => {
    await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [{ ...deviceParsed(swiggy), rawBody: swiggy }] })
      .expect(201);

    const stored = await prisma.bankAlert.findFirstOrThrow({ where: { userId: me.id } });
    expect(stored.rawBody).toBeNull();
    // The useful fields still came through.
    expect(stored.amountMinor).toBe(48_600);
    expect(stored.accountMask).toBe('4471');
    expect(stored.merchantRaw).toBe('SWIGGY');
  });

  it('keeps the body once the user turns that on, and forgets it when they turn it off', async () => {
    await request(app).patch('/v1/users/me').set(auth(me)).send({ keepAlertBodies: true }).expect(200);
    await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [{ ...deviceParsed(swiggy), rawBody: swiggy }] })
      .expect(201);

    let stored = await prisma.bankAlert.findFirstOrThrow({ where: { userId: me.id } });
    expect(stored.rawBody).toBe(swiggy);

    await request(app).patch('/v1/users/me').set(auth(me)).send({ keepAlertBodies: false }).expect(200);
    stored = await prisma.bankAlert.findFirstOrThrow({ where: { userId: me.id } });
    expect(stored.rawBody).toBeNull();
  });

  it('is idempotent, so a phone re-reading its inbox does not double anything', async () => {
    const alert = deviceParsed(swiggy);
    await request(app).post('/v1/alerts').set(auth(me)).send({ alerts: [alert] }).expect(201);
    const again = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [alert] })
      .expect(201);

    expect(again.body.created).toBe(0);
    expect(again.body.duplicates).toBe(1);
    expect(await prisma.bankAlert.count({ where: { userId: me.id } })).toBe(1);
  });

  it('takes a whole batch at once, as a first sync would', async () => {
    const response = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy), deviceParsed(croma, 'AD-ICICIB')] })
      .expect(201);
    expect(response.body.created).toBe(2);
  });

  it('rejects a full account number instead of a mask', async () => {
    await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({
        alerts: [{ ...deviceParsed(swiggy), accountMask: '4111111111111111' }]
      })
      .expect(422);
  });

  it('tells the device which rules version the server is on', async () => {
    const response = await request(app).get('/v1/alerts/rules/version').set(auth(me)).expect(200);
    expect(response.body.version).toBeGreaterThanOrEqual(1);
  });
});

describe('one tap from a debit to a personal expense', () => {
  it('creates a personal expense with no bill and no split', async () => {
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);
    const alertId = ingest.body.results[0].alertId;

    const response = await request(app)
      .post(`/v1/alerts/${alertId}/expense`)
      .set(auth(me))
      .send({})
      .expect(201);

    const expense = response.body.expense;
    expect(expense.kind).toBe('PERSONAL');
    expect(expense.source).toBe('ALERT');
    expect(expense.merchantName).toBe('SWIGGY');
    expect(expense.totals.totalMinor).toBe(48_600);
    expect(expense.items).toEqual([]);
    expect(expense.shares).toEqual([]);
    expect(expense.category.slug).toBe('restaurant');

    // The alert stops asking.
    const inbox = await request(app).get('/v1/alerts?status=UNMATCHED').set(auth(me)).expect(200);
    expect(inbox.body.alerts).toHaveLength(0);
  });

  it('accepts a category the user picked over the suggestion', async () => {
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(croma, 'AD-ICICIB')] })
      .expect(201);

    const response = await request(app)
      .post(`/v1/alerts/${ingest.body.results[0].alertId}/expense`)
      .set(auth(me))
      .send({ categorySlug: 'entertainment' })
      .expect(201);
    expect(response.body.expense.category.slug).toBe('entertainment');
    expect(response.body.expense.categorySource).toBe('USER');
  });

  it('will not file the same alert twice', async () => {
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);
    const alertId = ingest.body.results[0].alertId;

    await request(app).post(`/v1/alerts/${alertId}/expense`).set(auth(me)).send({}).expect(201);
    const second = await request(app)
      .post(`/v1/alerts/${alertId}/expense`)
      .set(auth(me))
      .send({})
      .expect(409);
    expect(second.body.error.code).toBe('ALREADY_LINKED');
  });

  it('refuses to turn a credit into an expense', async () => {
    const credit =
      'Credited Rs.643.00 to HDFC Bank A/C x4471 from ROHANKAMAT@okhdfcbank on 20-09-26. UPI Ref 528007712330';
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(credit)] })
      .expect(201);

    const response = await request(app)
      .post(`/v1/alerts/${ingest.body.results[0].alertId}/expense`)
      .set(auth(me))
      .send({})
      .expect(400);
    expect(response.body.error.code).toBe('CREDIT_NOT_AN_EXPENSE');
  });

  it('queues a notification for the debit, which is what reaches a closed app', async () => {
    await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);

    const notification = await prisma.notification.findFirstOrThrow({
      where: { userId: me.id, kind: 'ALERT_NEEDS_EXPENSE' }
    });
    expect(notification.title).toContain('486');
    expect(notification.title).toContain('SWIGGY');
    // And an outbox row so a worker actually delivers it.
    expect(await prisma.outboxEvent.count({ where: { topic: 'notification.push' } })).toBeGreaterThan(0);

    const data = notification.data as { alertId: string; suggestedCategorySlug: string };
    expect(data.alertId).toBeTruthy();
    expect(data.suggestedCategorySlug).toBe('restaurant');
  });
});

describe('matching a credit to a debt', () => {
  it('spots someone paying back exactly what they owe and settles it', async () => {
    // Aditi owes me 643.00 of a 1286.00 bill.
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Dinner',
        totalMinor: 128_600,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const before = await request(app).get('/v1/settlements/balance').set(auth(me)).expect(200);
    expect(before.body.balance.owedToYouMinor).toBe(64_300);

    const credit = `Credited Rs.643.00 to HDFC Bank A/C x4471 from ADITI@okhdfcbank on 20-09-26. UPI Ref 999888777`;
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(credit)] })
      .expect(201);

    expect(ingest.body.results[0].status).toBe('SETTLED');
    expect(ingest.body.results[0].matchedSettlementFor.userId).toBe(aditi.id);

    const after = await request(app).get('/v1/settlements/balance').set(auth(me)).expect(200);
    expect(after.body.balance.owedToYouMinor).toBe(0);
  });

  it('leaves a credit alone when it matches nobody', async () => {
    const credit = 'Credited Rs.5000.00 to HDFC Bank A/C x4471 from SALARY on 20-09-26. Ref 12345678';
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(credit)] })
      .expect(201);
    expect(ingest.body.results[0].status).toBe('UNMATCHED');
  });
});

describe('the inbox', () => {
  it('lists newest first with counts per status, and can be filtered', async () => {
    await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy), deviceParsed(croma, 'AD-ICICIB')] })
      .expect(201);

    const all = await request(app).get('/v1/alerts').set(auth(me)).expect(200);
    expect(all.body.alerts).toHaveLength(2);
    expect(all.body.counts.UNMATCHED).toBe(2);

    const ignored = all.body.alerts[0].id;
    await request(app).post(`/v1/alerts/${ignored}/ignore`).set(auth(me)).expect(204);

    const remaining = await request(app).get('/v1/alerts?status=UNMATCHED').set(auth(me)).expect(200);
    expect(remaining.body.alerts).toHaveLength(1);
  });

  it('offers link candidates for a bill already scanned at that amount', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Swiggy order', totalMinor: 48_600 })
      .expect(201);

    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);

    const suggestions = await request(app)
      .get(`/v1/alerts/${ingest.body.results[0].alertId}/suggestions`)
      .set(auth(me))
      .expect(200);

    expect(suggestions.body.linkCandidates).toHaveLength(1);
    expect(suggestions.body.linkCandidates[0].totalMinor).toBe(48_600);
    expect(suggestions.body.categories[0].slug).toBe('restaurant');
  });

  it('links an alert to an existing expense and flags an amount mismatch', async () => {
    const expense = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Swiggy', totalMinor: 50_000 })
      .expect(201);

    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);

    const link = await request(app)
      .post(`/v1/alerts/${ingest.body.results[0].alertId}/link`)
      .set(auth(me))
      .send({ expenseId: expense.body.expense.id })
      .expect(200);

    expect(link.body.amountsMatch).toBe(false);
    expect(link.body.differenceMinor).toBe(1400);
    expect(link.body.alert.status).toBe('LINKED');
  });

  it('keeps one person’s alerts away from another', async () => {
    const ingest = await request(app)
      .post('/v1/alerts')
      .set(auth(me))
      .send({ alerts: [deviceParsed(swiggy)] })
      .expect(201);

    await request(app)
      .post(`/v1/alerts/${ingest.body.results[0].alertId}/expense`)
      .set(auth(aditi))
      .send({})
      .expect(404);

    const theirInbox = await request(app).get('/v1/alerts').set(auth(aditi)).expect(200);
    expect(theirInbox.body.alerts).toHaveLength(0);
  });
});
