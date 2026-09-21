import { afterAll, beforeEach, describe, expect, it } from 'vitest';
import request from 'supertest';
import { prisma } from '../src/prisma-for-tests.js';
import { app, auth, resetDatabase, signIn } from './helpers.js';

beforeEach(resetDatabase);
afterAll(async () => {
  await prisma.$disconnect();
});

describe('email OTP sign-in', () => {
  it('sends a code and exchanges it for tokens', async () => {
    const start = await request(app)
      .post('/v1/auth/otp/start')
      .send({ contact: 'Misbah@Example.com' })
      .expect(202);

    expect(start.body.channel).toBe('EMAIL');
    expect(start.body.sentTo).not.toContain('misbah@example.com');
    expect(start.body.sentTo).toContain('@example.com');

    const verify = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'misbah@example.com', code: start.body.devCode, name: 'Misbah' })
      .expect(200);

    expect(verify.body.accessToken).toBeTruthy();
    expect(verify.body.refreshToken).toBeTruthy();
    expect(verify.body.user.email).toBe('misbah@example.com');
    expect(verify.body.user.name).toBe('Misbah');
  });

  it('normalises the email so casing and spacing do not fork the account', async () => {
    const first = await signIn('  Misbah@Example.COM ');
    const second = await signIn('misbah@example.com');
    expect(second.id).toBe(first.id);
  });

  it('refuses a wrong code and counts the attempt', async () => {
    const start = await request(app)
      .post('/v1/auth/otp/start')
      .send({ contact: 'a@example.com' })
      .expect(202);

    const wrong = start.body.devCode === '000000' ? '111111' : '000000';
    const failed = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'a@example.com', code: wrong })
      .expect(400);

    expect(failed.body.error.code).toBe('CODE_INCORRECT');
    const challenge = await prisma.otpChallenge.findFirstOrThrow({
      where: { destination: 'a@example.com' }
    });
    expect(challenge.attempts).toBe(1);
  });

  it('spends a code so it cannot be replayed', async () => {
    const start = await request(app).post('/v1/auth/otp/start').send({ contact: 'b@example.com' });
    await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'b@example.com', code: start.body.devCode })
      .expect(200);
    const replay = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'b@example.com', code: start.body.devCode })
      .expect(400);
    expect(replay.body.error.code).toBe('NO_CODE_PENDING');
  });

  it('invalidates an older code when a new one is asked for', async () => {
    const first = await request(app).post('/v1/auth/otp/start').send({ contact: 'c@example.com' });
    const second = await request(app).post('/v1/auth/otp/start').send({ contact: 'c@example.com' });

    const stale = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'c@example.com', code: first.body.devCode })
      .expect(400);
    expect(stale.body.error.code).toBe('CODE_INCORRECT');

    await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'c@example.com', code: second.body.devCode })
      .expect(200);
  });

  it('rejects an expired code', async () => {
    const start = await request(app).post('/v1/auth/otp/start').send({ contact: 'd@example.com' });
    await prisma.otpChallenge.updateMany({
      where: { destination: 'd@example.com' },
      data: { expiresAt: new Date(Date.now() - 1000) }
    });
    const expired = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'd@example.com', code: start.body.devCode })
      .expect(400);
    expect(expired.body.error.code).toBe('CODE_EXPIRED');
  });

  it('gives up after too many wrong guesses on one challenge', async () => {
    await request(app).post('/v1/auth/otp/start').send({ contact: 'e@example.com' });
    await prisma.otpChallenge.updateMany({
      where: { destination: 'e@example.com' },
      data: { attempts: 5 }
    });
    const blocked = await request(app)
      .post('/v1/auth/otp/verify')
      .send({ contact: 'e@example.com', code: '123456' })
      .expect(429);
    expect(blocked.body.error.code).toBe('RATE_LIMITED');
  });
});

describe('phone sign-in', () => {
  it('normalises Indian numbers written several ways to one account', async () => {
    const forms = ['+91 98200 11234', '098200 11234', '9820011234', '919820011234'];
    const ids = new Set<string>();

    for (const form of forms) {
      const start = await request(app).post('/v1/auth/otp/start').send({ contact: form }).expect(202);
      expect(start.body.channel).toBe('PHONE');
      const verify = await request(app)
        .post('/v1/auth/otp/verify')
        .send({ contact: form, code: start.body.devCode })
        .expect(200);
      ids.add(verify.body.user.id);
    }

    expect(ids.size).toBe(1);
  });

  it('masks the destination in the response', async () => {
    const start = await request(app)
      .post('/v1/auth/otp/start')
      .send({ contact: '+919820011234' })
      .expect(202);
    expect(start.body.sentTo).toContain('1234');
    expect(start.body.sentTo).not.toContain('9820011234');
  });

  it('rejects nonsense', async () => {
    await request(app).post('/v1/auth/otp/start').send({ contact: '12' }).expect(422);
    const bad = await request(app).post('/v1/auth/otp/start').send({ contact: 'abc def' }).expect(400);
    expect(bad.body.error.code).toBe('INVALID_PHONE');
  });
});

describe('sessions', () => {
  it('rotates the refresh token and revokes the old one', async () => {
    const user = await signIn('rotate@example.com');

    const first = await request(app)
      .post('/v1/auth/refresh')
      .send({ refreshToken: user.refreshToken })
      .expect(200);

    expect(first.body.refreshToken).not.toBe(user.refreshToken);

    const reused = await request(app)
      .post('/v1/auth/refresh')
      .send({ refreshToken: user.refreshToken })
      .expect(401);
    expect(reused.body.error.code).toBe('REFRESH_REUSED');
  });

  it('kills every session when a rotated token is replayed', async () => {
    const user = await signIn('replay@example.com');
    await request(app).post('/v1/auth/refresh').send({ refreshToken: user.refreshToken }).expect(200);
    await request(app).post('/v1/auth/refresh').send({ refreshToken: user.refreshToken }).expect(401);

    const live = await prisma.session.count({ where: { userId: user.id, revokedAt: null } });
    expect(live).toBe(0);

    // The access token from before the breach must stop working too.
    await request(app).get('/v1/users/me').set(auth(user)).expect(401);
  });

  it('refuses a request with no token, a malformed one, or a made-up one', async () => {
    await request(app).get('/v1/users/me').expect(401);
    await request(app).get('/v1/users/me').set({ Authorization: 'Basic abc' }).expect(401);
    await request(app).get('/v1/users/me').set({ Authorization: 'Bearer nonsense' }).expect(401);
  });

  it('logs out just this session', async () => {
    const user = await signIn('out@example.com');
    await request(app).post('/v1/auth/logout').set(auth(user)).expect(204);
    await request(app).get('/v1/users/me').set(auth(user)).expect(401);
  });

  it('logs out everywhere', async () => {
    const a = await signIn('many@example.com');
    const b = await signIn('many@example.com');
    const result = await request(app).post('/v1/auth/logout-everywhere').set(auth(b)).expect(200);
    expect(result.body.sessionsEnded).toBeGreaterThanOrEqual(2);
    await request(app).get('/v1/users/me').set(auth(a)).expect(401);
  });

  it('registers the device it was given at sign-in', async () => {
    const start = await request(app).post('/v1/auth/otp/start').send({ contact: 'dev@example.com' });
    const verify = await request(app)
      .post('/v1/auth/otp/verify')
      .send({
        contact: 'dev@example.com',
        code: start.body.devCode,
        device: { installId: 'install-abc-123', platform: 'ANDROID', pushToken: 'fcm-token-1' }
      })
      .expect(200);

    const device = await prisma.device.findFirstOrThrow({ where: { userId: verify.body.user.id } });
    expect(device.pushToken).toBe('fcm-token-1');
    expect(device.platform).toBe('ANDROID');
  });
});

describe('google sign-in', () => {
  it('reports itself as unavailable when no client id is configured', async () => {
    const methods = await request(app).get('/v1/auth/methods').expect(200);
    expect(methods.body.google).toBe(false);

    const attempt = await request(app)
      .post('/v1/auth/google')
      .send({ idToken: 'x'.repeat(40) })
      .expect(501);
    expect(attempt.body.error.code).toBe('GOOGLE_SIGN_IN_DISABLED');
  });
});

describe('claiming a placeholder account', () => {
  it('hands an invited placeholder its balances when the person finally signs in', async () => {
    const payer = await signIn('payer@example.com', 'Payer');

    // Split with somebody who has no account, by email.
    const expense = await request(app)
      .post('/v1/expenses')
      .set(auth(payer))
      .send({
        kind: 'SHARED',
        totalMinor: 20_000,
        merchantName: 'Chai stall',
        splitMethod: 'EQUAL',
        participants: [{ userId: payer.id }, { email: 'newcomer@example.com' }]
      })
      .expect(201);

    const placeholder = expense.body.expense.shares.find(
      (share: { user: { status: string } }) => share.user.status === 'INVITED'
    );
    expect(placeholder).toBeTruthy();
    expect(placeholder.amountMinor).toBe(10_000);

    // Now they sign in with that same address.
    const claimed = await signIn('newcomer@example.com', 'Newcomer');
    expect(claimed.id).toBe(placeholder.userId);

    const me = await request(app).get('/v1/users/me').set(auth(claimed)).expect(200);
    expect(me.body.user.status).toBe('ACTIVE');

    // And the debt is theirs, waiting.
    const balance = await request(app)
      .get('/v1/settlements/balance')
      .set(auth(claimed))
      .expect(200);
    expect(balance.body.balance.owedByYouMinor).toBe(10_000);
  });
});
