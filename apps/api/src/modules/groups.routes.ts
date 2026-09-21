import { Router } from 'express';
import { z } from 'zod';
import { SplitMethodSchema } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams, validateQuery } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';
import { assertGroupMember } from '../services/expenseService.js';
import { balancesFor } from '../services/balanceService.js';
import { findOrCreateByContact, publicUserFields } from '../services/userService.js';
import { enqueueNotification } from '../services/notificationService.js';
import { randomToken } from '../lib/crypto.js';
import { env } from '../env.js';
import { maskContact, normalizeContact } from '../lib/contact.js';

export const groupsRouter = Router();
groupsRouter.use(requireAuth);

const IdParam = z.object({ id: z.string().uuid() });

const CreateBody = z.object({
  name: z.string().trim().min(1).max(120),
  description: z.string().trim().max(1000).optional(),
  currency: z.string().regex(/^[A-Z]{3}$/).default('INR'),
  iconKey: z.string().trim().max(32).default('home'),
  defaultSplitMethod: SplitMethodSchema.default('EQUAL'),
  /** Emails or phone numbers to invite straight away. */
  invite: z.array(z.string().trim().min(3).max(200)).max(50).default([])
});

groupsRouter.get('/', async (req, res, next) => {
  try {
    const userId = req.user!.id;
    const groups = await prisma.group.findMany({
      where: { members: { some: { userId, leftAt: null } }, archivedAt: null },
      orderBy: { updatedAt: 'desc' },
      include: {
        members: {
          where: { leftAt: null },
          include: { user: { select: publicUserFields } }
        },
        _count: { select: { expenses: true } }
      }
    });

    // One balance report per group. Fine at this cardinality — people have a
    // handful of groups, not hundreds.
    const withBalances = await Promise.all(
      groups.map(async (group) => {
        const balance = await balancesFor(userId, group.id);
        return { ...serializeGroup(group), balance: {
          netMinor: balance.netMinor,
          owedToYouMinor: balance.owedToYouMinor,
          owedByYouMinor: balance.owedByYouMinor
        } };
      })
    );

    res.json({ groups: withBalances });
  } catch (error) {
    next(error);
  }
});

groupsRouter.post('/', writeLimiter, validateBody(CreateBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof CreateBody>;
    const userId = req.user!.id;

    const group = await prisma.group.create({
      data: {
        name: body.name,
        ...(body.description ? { description: body.description } : {}),
        currency: body.currency,
        iconKey: body.iconKey,
        defaultSplitMethod: body.defaultSplitMethod,
        createdById: userId,
        members: { create: { userId, role: 'OWNER' } }
      },
      select: { id: true }
    });

    const invited: Array<{ contact: string; userId: string; alreadyOnSnapTab: boolean }> = [];
    for (const contact of body.invite) {
      invited.push(await inviteToGroup(group.id, userId, contact));
    }

    const full = await prisma.group.findUniqueOrThrow({
      where: { id: group.id },
      include: {
        members: { where: { leftAt: null }, include: { user: { select: publicUserFields } } },
        _count: { select: { expenses: true } }
      }
    });

    res.status(201).json({ group: serializeGroup(full), invited });
  } catch (error) {
    next(error);
  }
});

groupsRouter.get('/:id', validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertGroupMember(id, req.user!.id, prisma);

    const [group, balance] = await Promise.all([
      prisma.group.findUniqueOrThrow({
        where: { id },
        include: {
          members: { where: { leftAt: null }, include: { user: { select: publicUserFields } } },
          invites: {
            where: { status: 'PENDING' },
            select: { id: true, channel: true, destination: true, createdAt: true, expiresAt: true }
          },
          _count: { select: { expenses: true } }
        }
      }),
      balancesFor(req.user!.id, id)
    ]);

    res.json({
      group: serializeGroup(group),
      pendingInvites: group.invites.map((invite) => ({
        id: invite.id,
        channel: invite.channel,
        // Never echo a full contact back in a group anyone in it can read.
        destination: invite.destination
          ? maskContact(normalizeContact(invite.destination))
          : null,
        createdAt: invite.createdAt,
        expiresAt: invite.expiresAt
      })),
      balance
    });
  } catch (error) {
    next(error);
  }
});

const PatchBody = CreateBody.partial().omit({ invite: true });

groupsRouter.patch('/:id', writeLimiter, validateParams(IdParam), validateBody(PatchBody), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertGroupRole(id, req.user!.id, ['OWNER', 'ADMIN']);
    const body = req.body as z.infer<typeof PatchBody>;

    const group = await prisma.group.update({
      where: { id },
      data: {
        ...(body.name ? { name: body.name } : {}),
        ...(body.description === undefined ? {} : { description: body.description }),
        ...(body.currency ? { currency: body.currency } : {}),
        ...(body.iconKey ? { iconKey: body.iconKey } : {}),
        ...(body.defaultSplitMethod ? { defaultSplitMethod: body.defaultSplitMethod } : {})
      },
      include: {
        members: { where: { leftAt: null }, include: { user: { select: publicUserFields } } },
        _count: { select: { expenses: true } }
      }
    });
    res.json({ group: serializeGroup(group) });
  } catch (error) {
    next(error);
  }
});

const MembersBody = z.object({
  /** Emails or phone numbers, exactly as typed. */
  contacts: z.array(z.string().trim().min(3).max(200)).min(1).max(50),
  displayNames: z.record(z.string().trim().max(120)).optional()
});

/**
 * Adds people by email or phone.
 *
 * Someone who has no SnapTab account gets a placeholder created for them and is
 * added as a real member: their share of a split is genuine money in the ledger
 * straight away, and they inherit it when they eventually sign in with the same
 * email or number.
 */
groupsRouter.post('/:id/members', writeLimiter, validateParams(IdParam), validateBody(MembersBody), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const body = req.body as z.infer<typeof MembersBody>;
    await assertGroupRole(id, req.user!.id, ['OWNER', 'ADMIN', 'MEMBER']);

    const added = [];
    for (const contact of body.contacts) {
      added.push(await inviteToGroup(id, req.user!.id, contact, body.displayNames?.[contact]));
    }

    const group = await prisma.group.findUniqueOrThrow({
      where: { id },
      include: {
        members: { where: { leftAt: null }, include: { user: { select: publicUserFields } } },
        _count: { select: { expenses: true } }
      }
    });

    res.status(201).json({ group: serializeGroup(group), added });
  } catch (error) {
    next(error);
  }
});

const MemberParam = z.object({ id: z.string().uuid(), userId: z.string().uuid() });

/**
 * Removes someone. Refused while they still owe or are owed anything — silently
 * dropping a member would make the group's balances stop adding up.
 */
groupsRouter.delete('/:id/members/:userId', writeLimiter, validateParams(MemberParam), async (req, res, next) => {
  try {
    const { id, userId } = req.params as unknown as z.infer<typeof MemberParam>;
    const actorId = req.user!.id;
    if (userId !== actorId) await assertGroupRole(id, actorId, ['OWNER', 'ADMIN']);

    const balance = await balancesFor(userId, id);
    if (balance.netMinor !== 0) {
      throw ApiError.conflict(
        'MEMBER_HAS_BALANCE',
        'Settle up with this person before removing them from the group.',
        { netMinor: balance.netMinor }
      );
    }

    await prisma.groupMember.update({
      where: { groupId_userId: { groupId: id, userId } },
      data: { leftAt: new Date() }
    });
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

groupsRouter.get('/:id/balance', validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertGroupMember(id, req.user!.id, prisma);
    res.json({ balance: await balancesFor(req.user!.id, id) });
  } catch (error) {
    next(error);
  }
});

const ActivityQuery = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(25)
});

groupsRouter.get('/:id/activity', validateParams(IdParam), validateQuery(ActivityQuery), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const { limit } = req.query as unknown as z.infer<typeof ActivityQuery>;
    await assertGroupMember(id, req.user!.id, prisma);

    const [expenses, settlements] = await Promise.all([
      prisma.expense.findMany({
        where: { groupId: id, status: { not: 'VOID' } },
        orderBy: { occurredAt: 'desc' },
        take: limit,
        select: {
          id: true,
          merchantName: true,
          totalMinor: true,
          currency: true,
          occurredAt: true,
          paidBy: { select: publicUserFields },
          category: { select: { slug: true, name: true, colorHex: true, tintHex: true } },
          shares: { where: { userId: req.user!.id }, select: { amountMinor: true, settledAt: true } }
        }
      }),
      prisma.settlement.findMany({
        where: { groupId: id, status: 'CONFIRMED' },
        orderBy: { createdAt: 'desc' },
        take: limit,
        select: {
          id: true,
          amountMinor: true,
          currency: true,
          method: true,
          note: true,
          createdAt: true,
          fromUser: { select: publicUserFields },
          toUser: { select: publicUserFields }
        }
      })
    ]);

    const feed = [
      ...expenses.map((expense) => ({
        type: 'EXPENSE' as const,
        at: expense.occurredAt,
        expense: {
          ...expense,
          yourShareMinor: expense.shares[0]?.amountMinor ?? 0,
          yourShareSettled: Boolean(expense.shares[0]?.settledAt)
        }
      })),
      ...settlements.map((settlement) => ({
        type: 'SETTLEMENT' as const,
        at: settlement.createdAt,
        settlement
      }))
    ]
      .sort((a, b) => b.at.getTime() - a.at.getTime())
      .slice(0, limit);

    res.json({ activity: feed });
  } catch (error) {
    next(error);
  }
});

// ------------------------------------------------------------------ helpers ---

async function assertGroupRole(
  groupId: string,
  userId: string,
  roles: Array<'OWNER' | 'ADMIN' | 'MEMBER'>
): Promise<void> {
  const membership = await prisma.groupMember.findUnique({
    where: { groupId_userId: { groupId, userId } },
    select: { role: true, leftAt: true }
  });
  if (!membership || membership.leftAt) {
    throw ApiError.forbidden('You are not in that group.', 'NOT_A_MEMBER');
  }
  if (!roles.includes(membership.role)) {
    throw ApiError.forbidden('Only a group admin can do that.', 'INSUFFICIENT_ROLE');
  }
}

async function inviteToGroup(
  groupId: string,
  invitedById: string,
  rawContact: string,
  displayName?: string
): Promise<{ contact: string; userId: string; alreadyOnSnapTab: boolean }> {
  const { user, created, contact } = await findOrCreateByContact(rawContact, displayName);

  await prisma.groupMember.upsert({
    where: { groupId_userId: { groupId, userId: user.id } },
    create: { groupId, userId: user.id, role: 'MEMBER' },
    update: { leftAt: null }
  });

  await prisma.groupInvite.upsert({
    where: { groupId_destination: { groupId, destination: contact.value } },
    create: {
      groupId,
      channel: contact.kind === 'EMAIL' ? 'EMAIL' : 'PHONE',
      destination: contact.value,
      token: randomToken(24),
      invitedById,
      invitedUserId: user.id,
      status: created ? 'PENDING' : 'ACCEPTED',
      expiresAt: new Date(Date.now() + 30 * 86_400_000),
      ...(created ? {} : { acceptedAt: new Date() })
    },
    update: { invitedUserId: user.id, status: created ? 'PENDING' : 'ACCEPTED' }
  });

  if (!created) {
    const group = await prisma.group.findUniqueOrThrow({
      where: { id: groupId },
      select: { name: true }
    });
    await prisma.$transaction(async (tx) => {
      await enqueueNotification(tx, {
        userId: user.id,
        kind: 'ADDED_TO_GROUP',
        title: `You were added to ${group.name}`,
        body: 'Open SnapTab to see what is on the tab.',
        data: { groupId }
      });
    });
  }

  return { contact: maskContact(contact), userId: user.id, alreadyOnSnapTab: !created };
}

type GroupRow = {
  id: string;
  name: string;
  description: string | null;
  currency: string;
  iconKey: string;
  defaultSplitMethod: string;
  createdAt: Date;
  updatedAt: Date;
  members: Array<{ role: string; joinedAt: Date; user: { id: string; name: string | null; email: string | null; phone: string | null; avatarUrl: string | null; status: string } }>;
  _count: { expenses: number };
};

function serializeGroup(group: GroupRow) {
  return {
    id: group.id,
    name: group.name,
    description: group.description,
    currency: group.currency,
    iconKey: group.iconKey,
    defaultSplitMethod: group.defaultSplitMethod,
    expenseCount: group._count.expenses,
    members: group.members.map((member) => ({
      role: member.role,
      joinedAt: member.joinedAt,
      user: {
        id: member.user.id,
        name: member.user.name,
        avatarUrl: member.user.avatarUrl,
        status: member.user.status,
        /** True for someone added by contact who has not signed in yet. */
        pending: member.user.status === 'INVITED'
      }
    })),
    shareBaseUrl: env.APP_SHARE_BASE_URL,
    createdAt: group.createdAt,
    updatedAt: group.updatedAt
  };
}
