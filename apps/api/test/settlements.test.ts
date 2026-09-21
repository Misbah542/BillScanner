import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { prisma } from '../src/prisma-for-tests.js';
import { app, auth, resetDatabase, seedCategories, signIn, type TestUser } from './helpers.js';

let me: TestUser;
let aditi: TestUser;

beforeEach(async () => {
  await resetDatabase();
  await seedCategories();
  me = await signIn('creditor@example.com', 'Creditor');
  aditi = await signIn('debtor@example.com', 'Debtor');
});

afterAll(async () => {
  await prisma.$disconnect();
});

async function billSplitInHalf(totalMinor: number) {
  const response = await request(app)
    .post('/v1/expenses')
    .set(auth(me))
    .send({
      kind: 'SHARED',
      merchantName: 'Dinner',
      totalMinor,
      splitMethod: 'EQUAL',
      participants: [{ userId: me.id }, { userId: aditi.id }]
    })
    .expect(201);
  return response.body.expense;
}

describe('recording a payment', () => {
  it('clears the debt and marks the expense settled', async () => {
    const expense = await billSplitInHalf(100_000);

    const settlement = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000, method: 'UPI' })
      .expect(201);

    expect(settlement.body.unappliedMinor).toBe(0);

    const balance = await request(app).get('/v1/settlements/balance').set(auth(me)).expect(200);
    expect(balance.body.balance.owedToYouMinor).toBe(0);
    // Both directions: a settled debt must not swing round into money I now owe.
    expect(balance.body.balance.owedByYouMinor).toBe(0);
    expect(balance.body.balance.netMinor).toBe(0);
    expect(balance.body.balance.people).toEqual([]);

    const row = await prisma.expense.findUniqueOrThrow({ where: { id: expense.id } });
    expect(row.status).toBe('SETTLED');
  });

  it('applies a part payment to the oldest debt first and leaves the rest open', async () => {
    await billSplitInHalf(100_000);
    await billSplitInHalf(60_000);

    await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000 })
      .expect(201);

    const balance = await request(app).get('/v1/settlements/balance').set(auth(me)).expect(200);
    expect(balance.body.balance.owedToYouMinor).toBe(30_000);
  });

  it('reports money paid over and above what was owed', async () => {
    await billSplitInHalf(100_000);
    const response = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 80_000 })
      .expect(201);
    expect(response.body.unappliedMinor).toBe(30_000);
  });

  it('refuses a settlement that names both sides, or neither', async () => {
    await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, toUserId: me.id, amountMinor: 1000 })
      .expect(422);
    await request(app).post('/v1/settlements').set(auth(me)).send({ amountMinor: 1000 }).expect(422);
  });

  it('refuses settling up with yourself', async () => {
    const response = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: me.id, amountMinor: 1000 })
      .expect(400);
    expect(response.body.error.code).toBe('SELF_SETTLEMENT');
  });

  it('notifies the other side', async () => {
    await billSplitInHalf(100_000);
    await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000, note: 'thanks!' })
      .expect(201);

    const notification = await prisma.notification.findFirstOrThrow({
      where: { userId: aditi.id, kind: 'SETTLEMENT_RECEIVED' }
    });
    expect(notification.body).toBe('thanks!');
  });
});

describe('undoing a settlement', () => {
  it('re-opens the debt it had closed', async () => {
    const expense = await billSplitInHalf(100_000);
    const settlement = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000 })
      .expect(201);

    await request(app)
      .delete(`/v1/settlements/${settlement.body.settlement.id}`)
      .set(auth(me))
      .expect(204);

    const balance = await request(app).get('/v1/settlements/balance').set(auth(me)).expect(200);
    expect(balance.body.balance.owedToYouMinor).toBe(50_000);

    const row = await prisma.expense.findUniqueOrThrow({ where: { id: expense.id } });
    expect(row.status).toBe('OPEN');
  });

  it('will not undo the same one twice', async () => {
    await billSplitInHalf(100_000);
    const settlement = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000 })
      .expect(201);

    await request(app).delete(`/v1/settlements/${settlement.body.settlement.id}`).set(auth(me)).expect(204);
    const again = await request(app)
      .delete(`/v1/settlements/${settlement.body.settlement.id}`)
      .set(auth(me))
      .expect(409);
    expect(again.body.error.code).toBe('ALREADY_REVERSED');
  });

  it('will not let an unrelated person undo it', async () => {
    await billSplitInHalf(100_000);
    const settlement = await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000 })
      .expect(201);

    const stranger = await signIn('nobody@example.com');
    await request(app)
      .delete(`/v1/settlements/${settlement.body.settlement.id}`)
      .set(auth(stranger))
      .expect(404);
  });
});

describe('reminders', () => {
  it('nudges someone who owes you', async () => {
    await billSplitInHalf(100_000);
    const response = await request(app)
      .post('/v1/settlements/remind')
      .set(auth(me))
      .send({ userId: aditi.id, message: 'whenever you get a chance' })
      .expect(202);
    expect(response.body.remindedMinor).toBe(50_000);

    const notification = await prisma.notification.findFirstOrThrow({
      where: { userId: aditi.id, kind: 'REMINDER_TO_PAY' }
    });
    expect(notification.title).toContain('500');
  });

  it('refuses to nudge somebody who owes nothing', async () => {
    const response = await request(app)
      .post('/v1/settlements/remind')
      .set(auth(me))
      .send({ userId: aditi.id })
      .expect(400);
    expect(response.body.error.code).toBe('NOTHING_OWED');
  });
});
