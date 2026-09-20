import { Router } from 'express';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateQuery } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';
import { normalizeContact } from '../lib/contact.js';
import { findByContact, publicUserFields } from '../services/userService.js';
import { markNotificationsRead } from '../services/notificationService.js';

export const usersRouter = Router();
usersRouter.use(requireAuth);

usersRouter.get('/me', async (req, res, next) => {
  try {
    const user = await prisma.user.findUniqueOrThrow({
      where: { id: req.user!.id },
      select: {
        ...publicUserFields,
        emailVerified: true,
        phoneVerified: true,
        currency: true,
        locale: true,
        timezone: true,
        keepAlertBodies: true,
        googleSub: true,
        createdAt: true
      }
    });
    res.json({
      user: {
        ...user,
        googleSub: undefined,
        googleLinked: Boolean(user.googleSub)
      }
    });
  } catch (error) {
    next(error);
  }
});

const PatchMe = z.object({
  name: z.string().trim().min(1).max(120).optional(),
  avatarUrl: z.string().url().max(512).nullable().optional(),
  currency: z.string().regex(/^[A-Z]{3}$/).optional(),
  locale: z.string().max(16).optional(),
  timezone: z.string().max(64).optional(),
  /**
   * Opt in to keeping the text of bank alerts. Off by default; only useful for
   * telling us why a message was read the way it was.
   */
  keepAlertBodies: z.boolean().optional()
});

usersRouter.patch('/me', writeLimiter, validateBody(PatchMe), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof PatchMe>;
    const user = await prisma.user.update({
      where: { id: req.user!.id },
      data: {
        ...(body.name ? { name: body.name } : {}),
        ...(body.avatarUrl === undefined ? {} : { avatarUrl: body.avatarUrl }),
        ...(body.currency ? { currency: body.currency } : {}),
        ...(body.locale ? { locale: body.locale } : {}),
        ...(body.timezone ? { timezone: body.timezone } : {}),
        ...(body.keepAlertBodies === undefined ? {} : { keepAlertBodies: body.keepAlertBodies })
      },
      select: { ...publicUserFields, currency: true, locale: true, timezone: true, keepAlertBodies: true }
    });

    // Turning the setting off should also forget what was already kept.
    if (body.keepAlertBodies === false) {
      await prisma.bankAlert.updateMany({
        where: { userId: req.user!.id, rawBody: { not: null } },
        data: { rawBody: null }
      });
    }

    res.json({ user });
  } catch (error) {
    next(error);
  }
});

const LookupQuery = z.object({ contact: z.string().trim().min(3).max(200) });

/**
 * "Is this person already on SnapTab?" — used by the add-people screen to show a
 * match before an invite is sent. Returns only a name and avatar, never an id or
 * contact detail for someone who is not a match, so it cannot be used to enumerate
 * accounts.
 */
usersRouter.get('/lookup', validateQuery(LookupQuery), async (req, res, next) => {
  try {
    const { contact } = req.query as unknown as z.infer<typeof LookupQuery>;
    const normalized = normalizeContact(contact);
    const user = await findByContact(normalized);

    res.json({
      found: Boolean(user && user.status !== 'INVITED'),
      user:
        user && user.status !== 'INVITED'
          ? { id: user.id, name: user.name, avatarUrl: user.avatarUrl }
          : null,
      kind: normalized.kind
    });
  } catch (error) {
    next(error);
  }
});

/** People you have split with before, for the add-people screen's suggestions. */
usersRouter.get('/recent', async (req, res, next) => {
  try {
    const userId = req.user!.id;
    const shares = await prisma.expenseShare.findMany({
      where: {
        userId: { not: userId },
        expense: { OR: [{ createdById: userId }, { paidById: userId }, { shares: { some: { userId } } }] }
      },
      orderBy: { expense: { occurredAt: 'desc' } },
      take: 120,
      select: {
        user: { select: publicUserFields },
        expense: { select: { occurredAt: true } }
      }
    });

    const seen = new Map<string, { user: unknown; lastSplitAt: Date; count: number }>();
    for (const share of shares) {
      const existing = seen.get(share.user.id);
      if (existing) {
        existing.count += 1;
      } else {
        seen.set(share.user.id, {
          user: { ...share.user, pending: share.user.status === 'INVITED' },
          lastSplitAt: share.expense.occurredAt,
          count: 1
        });
      }
    }

    res.json({
      people: [...seen.values()]
        .sort((a, b) => b.lastSplitAt.getTime() - a.lastSplitAt.getTime())
        .slice(0, 20)
    });
  } catch (error) {
    next(error);
  }
});

const NotificationsQuery = z.object({
  unreadOnly: z.coerce.boolean().default(false),
  limit: z.coerce.number().int().min(1).max(100).default(30)
});

usersRouter.get('/me/notifications', validateQuery(NotificationsQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof NotificationsQuery>;
    const [notifications, unread] = await Promise.all([
      prisma.notification.findMany({
        where: { userId: req.user!.id, ...(query.unreadOnly ? { readAt: null } : {}) },
        orderBy: { createdAt: 'desc' },
        take: query.limit
      }),
      prisma.notification.count({ where: { userId: req.user!.id, readAt: null } })
    ]);
    res.json({ notifications, unreadCount: unread });
  } catch (error) {
    next(error);
  }
});

const ReadBody = z.object({ ids: z.array(z.string().uuid()).max(200).default([]) });

usersRouter.post('/me/notifications/read', validateBody(ReadBody), async (req, res, next) => {
  try {
    const { ids } = req.body as z.infer<typeof ReadBody>;
    res.json({ markedRead: await markNotificationsRead(req.user!.id, ids) });
  } catch (error) {
    next(error);
  }
});

const DeviceBody = z.object({
  installId: z.string().trim().min(6).max(128),
  platform: z.enum(['ANDROID', 'IOS', 'WEB']).default('ANDROID'),
  pushToken: z.string().trim().max(512).optional(),
  appVersion: z.string().trim().max(32).optional(),
  osVersion: z.string().trim().max(32).optional(),
  model: z.string().trim().max(64).optional(),
  /** Whether the user granted SMS reading on this device. */
  smsEnabled: z.boolean().optional()
});

/** Registers or refreshes this device — push token and whether SMS reading is on. */
usersRouter.put('/me/device', writeLimiter, validateBody(DeviceBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof DeviceBody>;
    const device = await prisma.device.upsert({
      where: { userId_installId: { userId: req.user!.id, installId: body.installId } },
      create: {
        userId: req.user!.id,
        installId: body.installId,
        platform: body.platform,
        ...(body.pushToken ? { pushToken: body.pushToken } : {}),
        ...(body.appVersion ? { appVersion: body.appVersion } : {}),
        ...(body.osVersion ? { osVersion: body.osVersion } : {}),
        ...(body.model ? { model: body.model } : {}),
        ...(body.smsEnabled === undefined ? {} : { smsEnabled: body.smsEnabled })
      },
      update: {
        platform: body.platform,
        ...(body.pushToken ? { pushToken: body.pushToken } : {}),
        ...(body.appVersion ? { appVersion: body.appVersion } : {}),
        ...(body.osVersion ? { osVersion: body.osVersion } : {}),
        ...(body.smsEnabled === undefined ? {} : { smsEnabled: body.smsEnabled }),
        lastSeenAt: new Date()
      },
      select: { id: true, platform: true, smsEnabled: true, lastSeenAt: true }
    });
    res.json({ device });
  } catch (error) {
    next(error);
  }
});

/**
 * Deletes the account and everything hanging off it. Cascades are declared in the
 * schema, so this really does remove the data rather than flagging a row.
 */
usersRouter.delete('/me', writeLimiter, async (req, res, next) => {
  try {
    await prisma.user.delete({ where: { id: req.user!.id } });
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});
