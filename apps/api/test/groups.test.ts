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
  me = await signIn('owner@example.com', 'Owner');
  aditi = await signIn('member@example.com', 'Aditi');
  rohan = await signIn('member2@example.com', 'Rohan');
});

afterAll(async () => {
  await prisma.$disconnect();
});

async function makeGroup(invite: string[] = []) {
  const response = await request(app)
    .post('/v1/groups')
    .set(auth(me))
    .send({ name: 'Flat 402', iconKey: 'home', invite })
    .expect(201);
  return response.body;
}

describe('groups', () => {
  it('creates a group with the creator as owner', async () => {
    const { group } = await makeGroup();
    expect(group.name).toBe('Flat 402');
    expect(group.members).toHaveLength(1);
    expect(group.members[0].role).toBe('OWNER');
  });

  it('adds people by email and by phone in one go', async () => {
    const { group, invited } = await makeGroup(['member@example.com', '+91 99300 88214']);
    expect(group.members).toHaveLength(3);
    expect(invited).toHaveLength(2);

    // The one who already had an account is not "invited".
    expect(invited.find((entry: { alreadyOnSnapTab: boolean }) => entry.alreadyOnSnapTab)).toBeTruthy();
    // The phone number one is a placeholder.
    const placeholder = group.members.find((member: { user: { pending: boolean } }) => member.user.pending);
    expect(placeholder).toBeTruthy();
  });

  it('masks contacts in the invite list so a group cannot leak addresses', async () => {
    const { invited } = await makeGroup(['member@example.com']);
    expect(invited[0].contact).not.toBe('member@example.com');
    expect(invited[0].contact).toContain('@example.com');

    const { group } = await makeGroup(['nobody@example.com']);
    const detail = await request(app).get(`/v1/groups/${group.id}`).set(auth(me)).expect(200);
    for (const invite of detail.body.pendingInvites) {
      expect(invite.destination).not.toBe('nobody@example.com');
    }
  });

  it('adds members after the fact, idempotently', async () => {
    const { group } = await makeGroup();
    await request(app)
      .post(`/v1/groups/${group.id}/members`)
      .set(auth(me))
      .send({ contacts: ['member@example.com'] })
      .expect(201);
    const second = await request(app)
      .post(`/v1/groups/${group.id}/members`)
      .set(auth(me))
      .send({ contacts: ['member@example.com'] })
      .expect(201);
    expect(second.body.group.members).toHaveLength(2);
  });

  it('notifies someone who already has an account that they were added', async () => {
    const { group } = await makeGroup(['member@example.com']);
    const notification = await prisma.notification.findFirst({
      where: { userId: aditi.id, kind: 'ADDED_TO_GROUP' }
    });
    expect(notification?.title).toContain('Flat 402');
    expect((notification?.data as { groupId: string }).groupId).toBe(group.id);
  });

  it('keeps non-members out', async () => {
    const { group } = await makeGroup();
    const denied = await request(app).get(`/v1/groups/${group.id}`).set(auth(aditi)).expect(403);
    expect(denied.body.error.code).toBe('NOT_A_MEMBER');
  });

  it('lets only an admin rename the group', async () => {
    const { group } = await makeGroup(['member@example.com']);
    const denied = await request(app)
      .patch(`/v1/groups/${group.id}`)
      .set(auth(aditi))
      .send({ name: 'Hijacked' })
      .expect(403);
    expect(denied.body.error.code).toBe('INSUFFICIENT_ROLE');

    await request(app)
      .patch(`/v1/groups/${group.id}`)
      .set(auth(me))
      .send({ name: 'Flat 403' })
      .expect(200);
  });
});

describe('group balances', () => {
  it('reports what each member owes, and the fewest transfers to clear it', async () => {
    const { group } = await makeGroup(['member@example.com', 'member2@example.com']);

    // I pay 3000, split three ways.
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        groupId: group.id,
        merchantName: 'BigBasket',
        totalMinor: 300_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }, { userId: rohan.id }]
      })
      .expect(201);

    const balance = await request(app).get(`/v1/groups/${group.id}/balance`).set(auth(me)).expect(200);
    expect(balance.body.balance.owedToYouMinor).toBe(200_000);
    expect(balance.body.balance.owedByYouMinor).toBe(0);
    expect(balance.body.balance.people).toHaveLength(2);
    expect(balance.body.balance.suggestedTransfers).toHaveLength(2);

    // From Aditi's side the sign is the other way round.
    const theirs = await request(app)
      .get(`/v1/groups/${group.id}/balance`)
      .set(auth(aditi))
      .expect(200);
    expect(theirs.body.balance.owedByYouMinor).toBe(100_000);
    expect(theirs.body.balance.owedToYouMinor).toBe(0);
  });

  it('nets out expenses that went both ways', async () => {
    const { group } = await makeGroup(['member@example.com']);

    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        groupId: group.id,
        totalMinor: 100_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    await request(app)
      .post('/v1/expenses')
      .set(auth(aditi))
      .send({
        kind: 'SHARED',
        groupId: group.id,
        totalMinor: 60_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    // Aditi owes me 500, I owe Aditi 300 → net 200 to me.
    const balance = await request(app).get(`/v1/groups/${group.id}/balance`).set(auth(me)).expect(200);
    expect(balance.body.balance.netMinor).toBe(20_000);
    expect(balance.body.balance.people).toHaveLength(1);
    expect(balance.body.balance.suggestedTransfers).toHaveLength(1);
    expect(balance.body.balance.suggestedTransfers[0].amountMinor).toBe(20_000);
  });

  it('will not let someone leave owing money', async () => {
    const { group } = await makeGroup(['member@example.com']);
    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        groupId: group.id,
        totalMinor: 100_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }]
      })
      .expect(201);

    const denied = await request(app)
      .delete(`/v1/groups/${group.id}/members/${aditi.id}`)
      .set(auth(me))
      .expect(409);
    expect(denied.body.error.code).toBe('MEMBER_HAS_BALANCE');
  });

  it('lets a settled-up member go', async () => {
    const { group } = await makeGroup(['member@example.com']);
    await request(app).delete(`/v1/groups/${group.id}/members/${aditi.id}`).set(auth(me)).expect(204);

    const detail = await request(app).get(`/v1/groups/${group.id}`).set(auth(me)).expect(200);
    expect(detail.body.group.members).toHaveLength(1);
  });
});

describe('group activity', () => {
  it('interleaves expenses and settlements, newest first', async () => {
    const { group } = await makeGroup(['member@example.com']);

    await request(app)
      .post('/v1/expenses')
      .set(auth(me))
      .send({
        kind: 'SHARED',
        groupId: group.id,
        merchantName: 'BigBasket',
        totalMinor: 100_000,
        splitMethod: 'EQUAL',
        participants: [{ userId: me.id }, { userId: aditi.id }],
        occurredAt: '2026-09-18T10:00:00Z'
      })
      .expect(201);

    await request(app)
      .post('/v1/settlements')
      .set(auth(me))
      .send({ fromUserId: aditi.id, amountMinor: 50_000, groupId: group.id, method: 'UPI' })
      .expect(201);

    const activity = await request(app)
      .get(`/v1/groups/${group.id}/activity`)
      .set(auth(me))
      .expect(200);

    expect(activity.body.activity).toHaveLength(2);
    expect(activity.body.activity[0].type).toBe('SETTLEMENT');
    expect(activity.body.activity[1].type).toBe('EXPENSE');
    expect(activity.body.activity[1].expense.yourShareMinor).toBe(50_000);
  });
});
