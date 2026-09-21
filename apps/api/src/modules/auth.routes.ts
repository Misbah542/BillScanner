import { Router } from 'express';
import { z } from 'zod';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { env, googleSignInEnabled } from '../env.js';
import { ApiError } from '../lib/errors.js';
import { prisma } from '../lib/prisma.js';
import { logger } from '../lib/logger.js';
import { hashIp, randomOtp, randomToken, safeEqualHex, sha256 } from '../lib/crypto.js';
import { signAccessToken } from '../lib/tokens.js';
import { maskContact, normalizeContact, placeholderName } from '../lib/contact.js';
import { validateBody } from '../middleware/validate.js';
import { otpRequestLimiter, otpVerifyLimiter } from '../middleware/rateLimit.js';
import { requireAuth } from '../middleware/auth.js';
import { publicUserFields } from '../services/userService.js';

export const authRouter = Router();

const StartBody = z.object({
  /** An email address or a phone number — the client does not have to say which. */
  contact: z.string().trim().min(3).max(200)
});

/**
 * Step one of email/phone sign-in: send a six-digit code.
 *
 * The response is deliberately the same whether or not an account exists, so this
 * endpoint cannot be used to find out who has a SnapTab account.
 */
authRouter.post('/otp/start', otpRequestLimiter, validateBody(StartBody), async (req, res, next) => {
  try {
    const contact = normalizeContact((req.body as z.infer<typeof StartBody>).contact);
    const code = randomOtp(6);

    // Anything still outstanding for this destination is spent, so an older code
    // in a text message cannot be used after a newer one was requested.
    await prisma.otpChallenge.updateMany({
      where: { destination: contact.value, channel: contact.kind === 'EMAIL' ? 'EMAIL' : 'SMS', consumedAt: null },
      data: { consumedAt: new Date() }
    });

    await prisma.otpChallenge.create({
      data: {
        channel: contact.kind === 'EMAIL' ? 'EMAIL' : 'SMS',
        destination: contact.value,
        codeHash: sha256(code),
        expiresAt: new Date(Date.now() + env.OTP_TTL_SECONDS * 1000)
      }
    });

    await deliverCode(contact.kind, contact.value, code);

    res.status(202).json({
      channel: contact.kind,
      sentTo: maskContact(contact),
      expiresInSeconds: env.OTP_TTL_SECONDS,
      // Returned outside production only, so a checkout with no SMTP or SMS
      // gateway can still sign in, and so the tests do not need a back door.
      ...(env.NODE_ENV === 'production' ? {} : { devCode: code })
    });
  } catch (error) {
    next(error);
  }
});

const VerifyBody = z.object({
  contact: z.string().trim().min(3).max(200),
  code: z.string().trim().regex(/^\d{4,8}$/, 'That code does not look right.'),
  name: z.string().trim().min(1).max(120).optional(),
  device: z
    .object({
      installId: z.string().trim().min(6).max(128),
      platform: z.enum(['ANDROID', 'IOS', 'WEB']).default('ANDROID'),
      pushToken: z.string().trim().max(512).optional(),
      appVersion: z.string().trim().max(32).optional(),
      osVersion: z.string().trim().max(32).optional(),
      model: z.string().trim().max(64).optional()
    })
    .optional()
});

/**
 * Step two: exchange the code for tokens.
 *
 * If a placeholder user already exists for this contact — because somebody added
 * them to a split before they ever signed up — this is where it is claimed, with
 * every balance already attached to it.
 */
authRouter.post('/otp/verify', otpVerifyLimiter, validateBody(VerifyBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof VerifyBody>;
    const contact = normalizeContact(body.contact);
    const channel = contact.kind === 'EMAIL' ? 'EMAIL' : 'SMS';

    const challenge = await prisma.otpChallenge.findFirst({
      where: { destination: contact.value, channel, consumedAt: null },
      orderBy: { createdAt: 'desc' }
    });

    if (!challenge) throw ApiError.badRequest('NO_CODE_PENDING', 'Ask for a new code.');
    if (challenge.expiresAt < new Date()) {
      throw ApiError.badRequest('CODE_EXPIRED', 'That code has expired. Ask for a new one.');
    }
    if (challenge.attempts >= challenge.maxAttempts) {
      throw ApiError.tooManyRequests('Too many wrong codes for that address. Ask for a new one.');
    }

    if (!safeEqualHex(challenge.codeHash, sha256(body.code))) {
      await prisma.otpChallenge.update({
        where: { id: challenge.id },
        data: { attempts: { increment: 1 } }
      });
      const left = challenge.maxAttempts - challenge.attempts - 1;
      throw ApiError.badRequest(
        'CODE_INCORRECT',
        left > 0 ? `That code is not right. ${left} ${left === 1 ? 'try' : 'tries'} left.` : 'That code is not right.'
      );
    }

    await prisma.otpChallenge.update({
      where: { id: challenge.id },
      data: { consumedAt: new Date() }
    });

    const user = await claimOrCreateUser(contact, body.name);
    const tokens = await issueSession(user.id, user.status, req, body.device);

    res.json({ ...tokens, user });
  } catch (error) {
    next(error);
  }
});

const GoogleBody = z.object({
  idToken: z.string().min(20),
  device: VerifyBody.shape.device
});

let googleJwks: ReturnType<typeof createRemoteJWKSet> | undefined;

/**
 * Google sign-in. The client does the Google dance and sends us the id token; we
 * verify it against Google's keys and check the audience is one of ours — never
 * trusting the client's claim about who the token belongs to.
 */
authRouter.post('/google', otpVerifyLimiter, validateBody(GoogleBody), async (req, res, next) => {
  try {
    if (!googleSignInEnabled) {
      throw ApiError.notImplemented(
        'GOOGLE_SIGN_IN_DISABLED',
        'Google sign-in is not configured on this server. Set GOOGLE_CLIENT_IDS.'
      );
    }
    const body = req.body as z.infer<typeof GoogleBody>;

    googleJwks ??= createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));
    const { payload } = await jwtVerify(body.idToken, googleJwks, {
      issuer: ['https://accounts.google.com', 'accounts.google.com'],
      audience: env.GOOGLE_CLIENT_IDS
    }).catch(() => {
      throw ApiError.unauthorized('That Google sign-in could not be verified.', 'GOOGLE_TOKEN_INVALID');
    });

    const sub = payload.sub;
    const email = typeof payload.email === 'string' ? payload.email.toLowerCase() : undefined;
    const emailVerified = payload.email_verified === true;
    if (!sub) throw ApiError.unauthorized('That Google token has no subject.', 'GOOGLE_TOKEN_INVALID');

    const user = await linkGoogleAccount({
      sub,
      ...(email ? { email } : {}),
      emailVerified,
      ...(typeof payload.name === 'string' ? { name: payload.name } : {}),
      ...(typeof payload.picture === 'string' ? { picture: payload.picture } : {})
    });

    const tokens = await issueSession(user.id, user.status, req, body.device);
    res.json({ ...tokens, user });
  } catch (error) {
    next(error);
  }
});

const RefreshBody = z.object({ refreshToken: z.string().min(20) });

/**
 * Rotates the refresh token: the old one is revoked and points at its replacement.
 * Presenting an already-rotated token means it leaked, so the whole chain is killed
 * and the user has to sign in again.
 */
authRouter.post('/refresh', validateBody(RefreshBody), async (req, res, next) => {
  try {
    const { refreshToken } = req.body as z.infer<typeof RefreshBody>;
    const hash = sha256(refreshToken);

    const session = await prisma.session.findUnique({
      where: { refreshTokenHash: hash },
      select: {
        id: true,
        userId: true,
        revokedAt: true,
        replacedById: true,
        expiresAt: true,
        deviceId: true,
        user: { select: { status: true } }
      }
    });

    if (!session) throw ApiError.unauthorized('That refresh token is not valid.', 'REFRESH_INVALID');

    if (session.revokedAt || session.replacedById) {
      // Reuse of a rotated token: treat every session in the family as burnt.
      await prisma.session.updateMany({
        where: { userId: session.userId, revokedAt: null },
        data: { revokedAt: new Date() }
      });
      logger.warn({ userId: session.userId }, 'refresh token reuse detected; all sessions revoked');
      throw ApiError.unauthorized('Sign in again, please.', 'REFRESH_REUSED');
    }

    if (session.expiresAt < new Date()) {
      throw ApiError.unauthorized('That session expired. Sign in again.', 'REFRESH_EXPIRED');
    }

    const next_ = await rotateSession(session.id, session.userId, session.user.status, req, session.deviceId);
    res.json(next_);
  } catch (error) {
    next(error);
  }
});

authRouter.post('/logout', requireAuth, async (req, res, next) => {
  try {
    await prisma.session.update({
      where: { id: req.user!.sessionId },
      data: { revokedAt: new Date() }
    });
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

authRouter.post('/logout-everywhere', requireAuth, async (req, res, next) => {
  try {
    const result = await prisma.session.updateMany({
      where: { userId: req.user!.id, revokedAt: null },
      data: { revokedAt: new Date() }
    });
    res.json({ sessionsEnded: result.count });
  } catch (error) {
    next(error);
  }
});

authRouter.get('/methods', (_req, res) => {
  res.json({
    email: true,
    phone: true,
    google: googleSignInEnabled
  });
});

// ------------------------------------------------------------------ helpers ---

async function claimOrCreateUser(
  contact: { kind: 'EMAIL' | 'PHONE'; value: string },
  name?: string
) {
  const where = contact.kind === 'EMAIL' ? { email: contact.value } : { phone: contact.value };
  const existing = await prisma.user.findFirst({ where, select: { id: true, status: true } });

  if (existing) {
    const user = await prisma.user.update({
      where: { id: existing.id },
      data: {
        // An INVITED placeholder becomes a real account here, keeping its balances.
        status: 'ACTIVE',
        ...(contact.kind === 'EMAIL' ? { emailVerified: true } : { phoneVerified: true }),
        ...(name ? { name } : {}),
        lastSeenAt: new Date()
      },
      select: publicUserFields
    });
    await prisma.identity.upsert({
      where: {
        provider_providerSubject: {
          provider: contact.kind === 'EMAIL' ? 'EMAIL_OTP' : 'PHONE_OTP',
          providerSubject: contact.value
        }
      },
      create: {
        userId: user.id,
        provider: contact.kind === 'EMAIL' ? 'EMAIL_OTP' : 'PHONE_OTP',
        providerSubject: contact.value
      },
      update: {}
    });
    return user;
  }

  const user = await prisma.user.create({
    data: {
      ...(contact.kind === 'EMAIL'
        ? { email: contact.value, emailVerified: true }
        : { phone: contact.value, phoneVerified: true }),
      name: name ?? placeholderName(contact),
      status: 'ACTIVE',
      lastSeenAt: new Date(),
      identities: {
        create: {
          provider: contact.kind === 'EMAIL' ? 'EMAIL_OTP' : 'PHONE_OTP',
          providerSubject: contact.value
        }
      }
    },
    select: publicUserFields
  });
  return user;
}

async function linkGoogleAccount(profile: {
  sub: string;
  email?: string;
  emailVerified: boolean;
  name?: string;
  picture?: string;
}) {
  const bySub = await prisma.user.findUnique({
    where: { googleSub: profile.sub },
    select: { id: true }
  });
  if (bySub) {
    return prisma.user.update({
      where: { id: bySub.id },
      data: {
        status: 'ACTIVE',
        lastSeenAt: new Date(),
        ...(profile.name ? { name: profile.name } : {}),
        ...(profile.picture ? { avatarUrl: profile.picture } : {})
      },
      select: publicUserFields
    });
  }

  // Same verified email as an existing account: link rather than fork the person
  // into two accounts with two sets of balances.
  if (profile.email && profile.emailVerified) {
    const byEmail = await prisma.user.findUnique({
      where: { email: profile.email },
      select: { id: true }
    });
    if (byEmail) {
      const user = await prisma.user.update({
        where: { id: byEmail.id },
        data: {
          googleSub: profile.sub,
          emailVerified: true,
          status: 'ACTIVE',
          lastSeenAt: new Date(),
          ...(profile.name ? { name: profile.name } : {}),
          ...(profile.picture ? { avatarUrl: profile.picture } : {})
        },
        select: publicUserFields
      });
      await prisma.identity.create({
        data: { userId: user.id, provider: 'GOOGLE', providerSubject: profile.sub }
      });
      return user;
    }
  }

  return prisma.user.create({
    data: {
      googleSub: profile.sub,
      ...(profile.email ? { email: profile.email, emailVerified: profile.emailVerified } : {}),
      ...(profile.name ? { name: profile.name } : {}),
      ...(profile.picture ? { avatarUrl: profile.picture } : {}),
      status: 'ACTIVE',
      lastSeenAt: new Date(),
      identities: { create: { provider: 'GOOGLE', providerSubject: profile.sub } }
    },
    select: publicUserFields
  });
}

interface DeviceInput {
  installId: string;
  platform: 'ANDROID' | 'IOS' | 'WEB';
  pushToken?: string;
  appVersion?: string;
  osVersion?: string;
  model?: string;
}

async function issueSession(
  userId: string,
  status: string,
  req: Express.Request & { ip?: string; headers: Record<string, unknown> },
  device?: DeviceInput
) {
  let deviceId: string | null = null;
  if (device) {
    const row = await prisma.device.upsert({
      where: { userId_installId: { userId, installId: device.installId } },
      create: {
        userId,
        installId: device.installId,
        platform: device.platform,
        ...(device.pushToken ? { pushToken: device.pushToken } : {}),
        ...(device.appVersion ? { appVersion: device.appVersion } : {}),
        ...(device.osVersion ? { osVersion: device.osVersion } : {}),
        ...(device.model ? { model: device.model } : {})
      },
      update: {
        platform: device.platform,
        ...(device.pushToken ? { pushToken: device.pushToken } : {}),
        ...(device.appVersion ? { appVersion: device.appVersion } : {}),
        lastSeenAt: new Date()
      },
      select: { id: true }
    });
    deviceId = row.id;
  }

  const refreshToken = randomToken(48);
  const session = await prisma.session.create({
    data: {
      userId,
      refreshTokenHash: sha256(refreshToken),
      ...(deviceId ? { deviceId } : {}),
      ...(typeof req.headers['user-agent'] === 'string'
        ? { userAgent: String(req.headers['user-agent']).slice(0, 256) }
        : {}),
      ...(hashIp(req.ip, env.JWT_SECRET) ? { ipHash: hashIp(req.ip, env.JWT_SECRET)! } : {}),
      expiresAt: new Date(Date.now() + env.REFRESH_TOKEN_TTL_DAYS * 86_400_000)
    },
    select: { id: true }
  });

  const accessToken = await signAccessToken({ sub: userId, sid: session.id, status });
  return {
    accessToken,
    refreshToken,
    tokenType: 'Bearer' as const,
    expiresIn: env.ACCESS_TOKEN_TTL
  };
}

async function rotateSession(
  oldSessionId: string,
  userId: string,
  status: string,
  req: Express.Request & { ip?: string; headers: Record<string, unknown> },
  deviceId: string | null
) {
  const refreshToken = randomToken(48);
  const session = await prisma.session.create({
    data: {
      userId,
      refreshTokenHash: sha256(refreshToken),
      ...(deviceId ? { deviceId } : {}),
      ...(hashIp(req.ip, env.JWT_SECRET) ? { ipHash: hashIp(req.ip, env.JWT_SECRET)! } : {}),
      expiresAt: new Date(Date.now() + env.REFRESH_TOKEN_TTL_DAYS * 86_400_000)
    },
    select: { id: true }
  });

  await prisma.session.update({
    where: { id: oldSessionId },
    data: { revokedAt: new Date(), replacedById: session.id }
  });

  const accessToken = await signAccessToken({ sub: userId, sid: session.id, status });
  return {
    accessToken,
    refreshToken,
    tokenType: 'Bearer' as const,
    expiresIn: env.ACCESS_TOKEN_TTL
  };
}

/**
 * Sends the code. With no SMTP or SMS gateway configured — a fresh checkout, CI —
 * it logs the code instead, which keeps the flow usable in development without
 * pretending a message went out.
 */
async function deliverCode(kind: 'EMAIL' | 'PHONE', destination: string, code: string): Promise<void> {
  if (kind === 'EMAIL' && !env.SMTP_URL) {
    logger.info({ destination, code }, 'no SMTP configured — sign-in code logged instead of emailed');
    return;
  }
  if (kind === 'PHONE' && !env.SMS_GATEWAY_URL) {
    logger.info({ destination, code }, 'no SMS gateway configured — sign-in code logged instead of texted');
    return;
  }

  if (kind === 'PHONE') {
    const response = await fetch(env.SMS_GATEWAY_URL!, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        ...(env.SMS_GATEWAY_KEY ? { authorization: `Bearer ${env.SMS_GATEWAY_KEY}` } : {})
      },
      body: JSON.stringify({ to: destination, text: `${code} is your SnapTab code. It expires in 10 minutes.` })
    });
    if (!response.ok) {
      logger.error({ status: response.status }, 'sms gateway rejected the code');
      throw new ApiError(502, 'SMS_SEND_FAILED', 'Could not text that code. Try email instead.');
    }
    return;
  }

  // Email delivery goes through whatever transport SMTP_URL points at; wiring a
  // mailer is deployment-specific, so it is left as one place to change.
  logger.warn({ destination }, 'SMTP_URL is set but no mailer is wired up — implement deliverCode');
  throw new ApiError(
    502,
    'EMAIL_SEND_FAILED',
    'Email delivery is not wired up on this server yet. Use phone sign-in.'
  );
}
