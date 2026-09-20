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
  me = await signIn('spender@example.com', 'Spender');
  aditi = await signIn('friend@example.com', 'Aditi');
  rohan = await signIn('friend2@example.com', 'Rohan');
  // A stable timezone so month boundaries in the assertions are predictable.
  await request(app).patch('/v1/users/me').set(auth(me)).send({ timezone: 'Asia/Kolkata' }).expect(200);
});

afterAll(async () => {
  await prisma.$disconnect();
});

const SEPT = '2026-09';
function inSept(day: number, hour = 12): string {
  return new Date(Date.UTC(2026, 8, day, hour - 5, 30)).toISOString();
}

describe('what "spent this month" means', () => {
  it('counts a personal expense in full', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'SWIGGY', totalMinor: 48_600, occurredAt: inSept(20) })
      .expect(201);

    const response = await request(app)
      .get(`/v1/insights/monthly?month=${SEPT}`)
      .set(auth(me))
      .expect(200);

    const summary = response.body.summary;
    expect(summary.personalMinor).toBe(48_600);
    expect(summary.sharedShareMinor).toBe(0);
    expect(summary.spentMinor).toBe(48_600);
    expect(summary.personalCount).toBe(1);
  });

  it('counts only YOUR SHARE of a bill you paid for everyone', async () => {
    // I pay 2575 for four people. My spending is 643.75, not 2575.
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Social Offline',
        totalMinor: 257_500,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }, { userId: rohan.id }],
        occurredAt: inSept(19)
      })
      .expect(201);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;

    expect(summary.sharedShareMinor).toBe(85_834);
    expect(summary.spentMinor).toBe(85_834);
    // What actually left my account is reported separately — it is a loan, not spend.
    expect(summary.paidOutMinor).toBe(257_500);
    expect(summary.owedToYouMinor).toBe(171_666);
  });

  it('counts your share of a bill somebody else paid', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(aditi))
      .send({
        kind: 'SHARED',
        merchantName: 'Uber',
        totalMinor: 64_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: aditi.id }, { userId: me.id }],
        occurredAt: inSept(18)
      })
      .expect(201);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;

    expect(summary.sharedShareMinor).toBe(32_000);
    expect(summary.spentMinor).toBe(32_000);
    // I paid nothing at the till.
    expect(summary.paidOutMinor).toBe(0);
    expect(summary.owedByYouMinor).toBe(32_000);
  });

  it('adds personal and shared together, and can report either on its own', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'SWIGGY', totalMinor: 48_600, occurredAt: inSept(20) })
      .expect(201);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Dinner',
        totalMinor: 100_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }],
        occurredAt: inSept(19)
      })
      .expect(201);

    const all = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;
    expect(all.spentMinor).toBe(48_600 + 50_000);
    expect(all.expenseCount).toBe(2);

    const personalOnly = (
      await request(app)
        .get(`/v1/insights/monthly?month=${SEPT}&kind=PERSONAL`)
        .set(auth(me))
        .expect(200)
    ).body.summary;
    expect(personalOnly.spentMinor).toBe(48_600);
    expect(personalOnly.sharedShareMinor).toBe(0);

    const sharedOnly = (
      await request(app)
        .get(`/v1/insights/monthly?month=${SEPT}&kind=SHARED`)
        .set(auth(me))
        .expect(200)
    ).body.summary;
    expect(sharedOnly.spentMinor).toBe(50_000);
    expect(sharedOnly.personalMinor).toBe(0);
  });

  it('leaves a deleted expense out', async () => {
    const created = await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', merchantName: 'Oops', totalMinor: 99_900, occurredAt: inSept(20) })
      .expect(201);
    await request(app).delete(`/v1/expenses/${created.body.expense.id}`).set(auth(me)).expect(204);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;
    expect(summary.spentMinor).toBe(0);
  });
});

describe('the category breakdown', () => {
  it('mixes personal and shared spend into one set of buckets', async () => {
    // A solo Swiggy order and a quarter of Saturday's dinner both count as
    // Restaurants, which is the whole point of the breakdown.
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        merchantName: 'SWIGGY',
        totalMinor: 50_000,
        categorySlug: 'restaurant',
        occurredAt: inSept(20)
      })
      .expect(201);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        merchantName: 'Social Offline',
        totalMinor: 100_000,
        categorySlug: 'restaurant',
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }],
        occurredAt: inSept(19)
      })
      .expect(201);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        merchantName: 'BigBasket',
        totalMinor: 50_000,
        categorySlug: 'groceries',
        occurredAt: inSept(18)
      })
      .expect(201);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;

    expect(summary.spentMinor).toBe(150_000);
    const restaurants = summary.byCategory.find((b: { slug: string }) => b.slug === 'restaurant');
    expect(restaurants.spentMinor).toBe(100_000);
    expect(restaurants.count).toBe(2);
    expect(restaurants.shareBp).toBe(6667);

    const groceries = summary.byCategory.find((b: { slug: string }) => b.slug === 'groceries');
    expect(groceries.spentMinor).toBe(50_000);

    // Sorted biggest first, and the shares add up to roughly 100%.
    expect(summary.byCategory[0].slug).toBe('restaurant');
    const totalBp = summary.byCategory.reduce((a: number, b: { shareBp: number }) => a + b.shareBp, 0);
    expect(Math.abs(totalBp - 10_000)).toBeLessThanOrEqual(2);
  });

  it('buckets an expense with no category as Uncategorised', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', totalMinor: 10_000, occurredAt: inSept(20) })
      .expect(201);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;
    expect(summary.byCategory[0].slug).toBe('other');
  });
});

describe('month boundaries', () => {
  it('keeps a late-night order in the right month for the user’s timezone', async () => {
    // 00:30 on 1 October in India is 19:00 on 30 September UTC. It belongs to
    // October for this user, and getting that wrong is exactly what makes people
    // stop trusting the number.
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'PERSONAL',
        merchantName: 'Late Swiggy',
        totalMinor: 30_000,
        occurredAt: '2026-09-30T19:00:00Z'
      })
      .expect(201);

    const september = (
      await request(app).get('/v1/insights/monthly?month=2026-09').set(auth(me)).expect(200)
    ).body.summary;
    const october = (
      await request(app).get('/v1/insights/monthly?month=2026-10').set(auth(me)).expect(200)
    ).body.summary;

    expect(september.spentMinor).toBe(0);
    expect(october.spentMinor).toBe(30_000);
  });

  it('reports last month alongside this one', async () => {
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', totalMinor: 20_000, occurredAt: '2026-08-15T06:00:00Z' })
      .expect(201);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({ kind: 'PERSONAL', totalMinor: 50_000, occurredAt: inSept(15) })
      .expect(201);

    const summary = (
      await request(app).get(`/v1/insights/monthly?month=${SEPT}`).set(auth(me)).expect(200)
    ).body.summary;
    expect(summary.spentMinor).toBe(50_000);
    expect(summary.previousSpentMinor).toBe(20_000);
  });

  it('refuses a malformed month', async () => {
    await request(app).get('/v1/insights/monthly?month=2026-13').set(auth(me)).expect(422);
    await request(app).get('/v1/insights/monthly?month=September').set(auth(me)).expect(422);
  });
});

describe('trend and merchants', () => {
  it('returns one point per month, oldest first', async () => {
    const response = await request(app).get('/v1/insights/trend?months=4').set(auth(me)).expect(200);
    expect(response.body.points).toHaveLength(4);
    const months = response.body.points.map((point: { month: string }) => point.month);
    expect([...months].sort()).toEqual(months);
  });

  it('ranks merchants by spend', async () => {
    for (const [merchant, amount] of [
      ['SWIGGY', 50_000],
      ['SWIGGY', 30_000],
      ['BigBasket', 60_000]
    ] as const) {
      await request(app)
        .post('/v1/expenses')
        .set(auth(me))
        .send({ kind: 'PERSONAL', merchantName: merchant, totalMinor: amount, occurredAt: inSept(15) })
        .expect(201);
    }

    const response = await request(app)
      .get(`/v1/insights/merchants?month=${SEPT}`)
      .set(auth(me))
      .expect(200);

    expect(response.body.merchants[0].spentMinor).toBe(80_000);
    expect(response.body.merchants[0].count).toBe(2);
    expect(response.body.merchants[1].merchant).toBe('BigBasket');
  });
});
