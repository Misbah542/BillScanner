import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { prisma } from '../src/prisma-for-tests.js';
import { app, auth, resetDatabase, seedCategories, signIn, type TestUser } from './helpers.js';

let me: TestUser;
let aditi: TestUser;
let rohan: TestUser;

beforeEach(async () => {
  await resetDatabase();
  await seedCategories();
  me = await signIn('me@example.com', 'Me');
  aditi = await signIn('aditi@example.com', 'Aditi');
  rohan = await signIn('rohan@example.com', 'Rohan');
});

afterAll(async () => {
  await prisma.$disconnect();
});

describe('personal expenses — the no-bill case', () => {
  it('records a lone card debit with no items and no split', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        source: 'ALERT',
        merchantName: 'SWIGGY',
        totalMinor: 48_600
      })
      .expect(201);

    const expense = response.body.expense;
    expect(expense.kind).toBe('PERSONAL');
    expect(expense.items).toEqual([]);
    expect(expense.shares).toEqual([]);
    expect(expense.splitMethod).toBeNull();
    expect(expense.totals.totalMinor).toBe(48_600);
    // The whole thing is yours.
    expect(expense.viewer.yourShareMinor).toBe(48_600);
    expect(expense.viewer.youAreOwedMinor).toBe(0);
  });

  it('guesses a category from the merchant and says it was a guess', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'BigBasket', totalMinor: 314_000 })
      .expect(201);

    expect(response.body.expense.category.slug).toBe('groceries');
    expect(response.body.expense.categorySource).toBe('SUGGESTED');
    expect(response.body.expense.categoryConfidence).toBeGreaterThan(0.45);
  });

  it('marks a category the user chose as theirs, and learns the merchant', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Blue Tokai', totalMinor: 45_000, categorySlug: 'groceries' })
      .expect(201);

    const memory = await prisma.merchantCategoryMemory.findFirst({
      where: { userId: me.id, merchantNormalized: 'blue tokai' },
      include: { category: true }
    });
    expect(memory?.category.slug).toBe('groceries');

    // And the next time that merchant comes up, the memory wins.
    const suggestions = await request(app)
      .get('/v1/expenses/suggest/category?merchant=Blue%20Tokai')
      .set(auth(me))
      .expect(200);
    expect(suggestions.body.suggestions[0].slug).toBe('groceries');
    expect(suggestions.body.suggestions[0].reasons.join(' ')).toMatch(/filed this merchant/);
  });

  it('refuses participants on a personal expense', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        totalMinor: 1000,
        participants: [{ userId: aditi.id }]
      })
      .expect(422);
    expect(response.body.error.code).toBe('VALIDATION_FAILED');
  });

  it('refuses a fractional amount rather than rounding it silently', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', totalMinor: 486.5 })
      .expect(422);
  });

  it('lists personal expenses separately from shared ones', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'SWIGGY', totalMinor: 48_600 })
      .expect(201);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Social Offline',
        totalMinor: 257_500,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const all = await request(app).get('/v1/expenses').set(auth(me)).expect(200);
    const personal = await request(app).get('/v1/expenses?kind=personal').set(auth(me)).expect(200);
    const shared = await request(app).get('/v1/expenses?kind=shared').set(auth(me)).expect(200);

    expect(all.body.expenses).toHaveLength(2);
    expect(personal.body.expenses).toHaveLength(1);
    expect(personal.body.expenses[0].merchantName).toBe('SWIGGY');
    expect(shared.body.expenses).toHaveLength(1);
  });
});

describe('shared expenses and splits', () => {
  const bill = {
    kind: 'SHARED' as const,
    source: 'SCAN' as const,
    merchantName: 'Social Offline, Bandra',
    itemTotalMinor: 228_500,
    serviceChargeMinor: 22_850,
    taxMinor: 6168,
    roundOffMinor: -18,
    totalMinor: 257_500,
    items: [
      { name: 'Aerated Beverages', quantityMilli: 2000, unitPriceMinor: 12_000, amountMinor: 24_000 },
      { name: 'Chilli Cheese Toast', quantityMilli: 1000, unitPriceMinor: 34_500, amountMinor: 34_500 },
      { name: 'LIIT Pitcher', quantityMilli: 2000, unitPriceMinor: 65_000, amountMinor: 130_000 },
      { name: 'Butter Chicken', quantityMilli: 1000, unitPriceMinor: 40_000, amountMinor: 40_000 }
    ]
  };

  it('splits equally and the shares add up to the exact total', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }, { userId: rohan.id }]
      })
      .expect(201);

    const shares = response.body.expense.shares;
    expect(shares).toHaveLength(3);
    expect(shares.reduce((a: number, s: { amountMinor: number }) => a + s.amountMinor, 0)).toBe(257_500);
    // 257500 / 3 leaves a paisa over; the payer takes it.
    const mine = shares.find((s: { userId: string }) => s.userId === me.id);
    expect(mine.amountMinor).toBe(85_834);
  });

  it('marks the payer as having already paid their own share', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ ...bill, splitMethod: 'EQUAL', participants: [{ userId: me.id }, { userId: aditi.id }] })
      .expect(201);

    const mine = response.body.expense.shares.find((s: { userId: string }) => s.userId === me.id);
    expect(mine.paidMinor).toBe(mine.amountMinor);
    expect(mine.settledAt).toBeTruthy();
    expect(response.body.expense.viewer.youAreOwedMinor).toBe(128_750);
  });

  it('splits by exact amounts and rejects a set that does not add up', async () => {
    const ok = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EXACT',
        participants: [
          { userId: me.id, value: 150_000 },
          { userId: aditi.id, value: 107_500 }
        ]
      })
      .expect(201);
    expect(ok.body.expense.shares.map((s: { amountMinor: number }) => s.amountMinor)).toEqual([150_000, 107_500]);

    const bad = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EXACT',
        participants: [
          { userId: me.id, value: 100_000 },
          { userId: aditi.id, value: 100_000 }
        ]
      })
      .expect(422);

    expect(bad.body.error.code).toBe('SPLIT_EXACT_SUM_MISMATCH');
    expect(bad.body.error.details.differenceMinor).toBe(57_500);
  });

  it('splits by percentage and rejects percentages that miss 100%', async () => {
    const ok = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'PERCENT',
        participants: [
          { userId: me.id, value: 5000 },
          { userId: aditi.id, value: 3000 },
          { userId: rohan.id, value: 2000 }
        ]
      })
      .expect(201);
    expect(ok.body.expense.shares.map((s: { amountMinor: number }) => s.amountMinor)).toEqual([
      128_750, 77_250, 51_500
    ]);

    const bad = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'PERCENT',
        participants: [
          { userId: me.id, value: 5000 },
          { userId: aditi.id, value: 4000 }
        ]
      })
      .expect(422);
    expect(bad.body.error.code).toBe('SPLIT_PERCENT_SUM_MISMATCH');
    expect(bad.body.error.message).toContain('90%');
  });

  it('splits by shares', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'SHARES',
        participants: [
          { userId: me.id, value: 2 },
          { userId: aditi.id, value: 1 },
          { userId: rohan.id, value: 1 }
        ]
      })
      .expect(201);
    expect(response.body.expense.shares.map((s: { amountMinor: number }) => s.amountMinor)).toEqual([
      128_750, 64_375, 64_375
    ]);
  });

  it('splits item by item and spreads the charges in proportion', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ ...bill, kind: 'PERSONAL' })
      .expect(201);

    const items = created.body.expense.items;
    const split = await request(app)
      .put(`/v1/expenses/${created.body.expense.id}/split`)
      .set(auth(me))
      .send({
        method: 'ITEMIZED',
        participants: [{ userId: me.id }, { userId: aditi.id }, { userId: rohan.id }],
        itemAssignments: [
          { itemId: items[0].id, assignments: [{ userId: me.id }, { userId: aditi.id }] },
          { itemId: items[1].id, assignments: [{ userId: rohan.id }] },
          {
            itemId: items[2].id,
            assignments: [{ userId: me.id }, { userId: aditi.id }, { userId: rohan.id }]
          },
          { itemId: items[3].id, assignments: [{ userId: aditi.id }] }
        ]
      })
      .expect(200);

    const shares = split.body.expense.shares;
    expect(shares.reduce((a: number, s: { amountMinor: number }) => a + s.amountMinor, 0)).toBe(257_500);
    expect(split.body.expense.kind).toBe('SHARED');
    expect(split.body.expense.splitMethod).toBe('ITEMIZED');

    // Every item records who was on it.
    const toast = split.body.expense.items.find((i: { name: string }) => i.name === 'Chilli Cheese Toast');
    expect(toast.assignedTo).toHaveLength(1);
    expect(toast.assignedTo[0].userId).toBe(rohan.id);
  });

  it('turns a personal expense into a shared one and back', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Chai', totalMinor: 30_000 })
      .expect(201);
    const id = created.body.expense.id;

    const shared = await request(app)
      .put(`/v1/expenses/${id}/split`)
      .set(auth(me))
      .send({ method: 'EQUAL', participants: [{ userId: me.id }, { userId: aditi.id }] })
      .expect(200);
    expect(shared.body.expense.kind).toBe('SHARED');
    expect(shared.body.expense.shares).toHaveLength(2);

    const personal = await request(app).delete(`/v1/expenses/${id}/split`).set(auth(me)).expect(200);
    expect(personal.body.expense.kind).toBe('PERSONAL');
    expect(personal.body.expense.shares).toEqual([]);
  });

  it('adds someone who has no account by email, as a real share', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { email: 'sneha@example.com', displayName: 'Sneha' }]
      })
      .expect(201);

    const invited = response.body.expense.shares.find(
      (s: { user: { status: string } }) => s.user.status === 'INVITED'
    );
    expect(invited.user.name).toBe('Sneha');
    expect(invited.amountMinor).toBe(128_750);
  });

  it('adds someone by phone number', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { phone: '+919930088214' }]
      })
      .expect(201);
    expect(response.body.expense.shares).toHaveLength(2);

    const user = await prisma.user.findUniqueOrThrow({ where: { phone: '+919930088214' } });
    expect(user.status).toBe('INVITED');
  });

  it('refuses the same person twice in one split', async () => {
    const response = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        ...bill,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: me.id }]
      })
      .expect(400);
    expect(response.body.error.code).toBe('DUPLICATE_PARTICIPANT');
  });

  it('requires a split method for a shared expense', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'SHARED', totalMinor: 1000 })
      .expect(422);
  });

  it('rejects items that do not add up to the stated item total', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        totalMinor: 100_000,
        itemTotalMinor: 100_000,
        items: [{ name: 'Thing', unitPriceMinor: 5000, amountMinor: 5000 }]
      })
      .expect(422);
  });
});

describe('who can see and change an expense', () => {
  it('lets a participant read it but not a stranger', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        totalMinor: 20_000,
        merchantName: 'Chai',
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);
    const id = created.body.expense.id;

    await request(app).get(`/v1/expenses/${id}`).set(auth(aditi)).expect(200);

    const stranger = await signIn('stranger@example.com');
    const denied = await request(app).get(`/v1/expenses/${id}`).set(auth(stranger)).expect(403);
    expect(denied.body.error.code).toBe('NOT_YOUR_EXPENSE');
  });

  it('will not let a participant who did not pay edit it', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        totalMinor: 20_000,
        merchantName: 'Chai',
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const denied = await request(app)
      .patch(`/v1/expenses/${created.body.expense.id}`)
      .set(auth(aditi))
      .send({ merchantName: 'Not chai' })
      .expect(403);
    expect(denied.body.error.code).toBe('NOT_EXPENSE_OWNER');
  });

  it('soft-deletes so history stays explicable', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Chai', totalMinor: 20_000 })
      .expect(201);

    await request(app).delete(`/v1/expenses/${created.body.expense.id}`).set(auth(me)).expect(204);

    const row = await prisma.expense.findUniqueOrThrow({ where: { id: created.body.expense.id } });
    expect(row.status).toBe('VOID');

    const list = await request(app).get('/v1/expenses').set(auth(me)).expect(200);
    expect(list.body.expenses).toHaveLength(0);
  });

  it('refuses to make an expense personal while it still has a split', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        totalMinor: 20_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const response = await request(app)
      .patch(`/v1/expenses/${created.body.expense.id}`)
      .set(auth(me))
      .send({ kind: 'PERSONAL' })
      .expect(409);
    expect(response.body.error.code).toBe('HAS_SHARES');
  });
});

describe('paging', () => {
  it('walks the list with a cursor and never repeats a row', async () => {
    for (let index = 0; index < 7; index += 1) {
      await request(app)
        .post('/v1/expenses')
        .set(auth(me))
        .send({
          kind: 'PERSONAL',
          merchantName: `Shop ${index}`,
          totalMinor: 1000 + index,
          occurredAt: new Date(Date.UTC(2026, 8, index + 1)).toISOString()
        })
        .expect(201);
    }

    const seen: string[] = [];
    let cursor: string | null = null;
    for (let page = 0; page < 5; page += 1) {
      // Both annotated: without them TypeScript walks url -> cursor -> response
      // -> url and gives up with an implicit-any circularity error.
      const url: string = cursor ? `/v1/expenses?limit=3&cursor=${cursor}` : '/v1/expenses?limit=3';
      const response: request.Response = await request(app).get(url).set(auth(me)).expect(200);
      seen.push(...response.body.expenses.map((expense: { id: string }) => expense.id));
      cursor = response.body.nextCursor;
      if (!cursor) break;
    }

    expect(seen).toHaveLength(7);
    expect(new Set(seen).size).toBe(7);
  });
});
