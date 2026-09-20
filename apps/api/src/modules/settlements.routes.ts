import { Router } from 'express';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams, validateQuery } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';
import { balancesFor } from '../services/balanceService.js';
import { enqueueNotification } from '../services/notificationService.js';
import { publicUserFields } from '../services/userService.js';

export const settlementsRouter = Router();
settlementsRouter.use(requireAuth);

const IdParam = z.object({ id: z.string().uuid() });

/** Everything you owe and are owed, plus the fewest transfers that would clear it. */
settlementsRouter.get('/balance', async (req, res, next) => {
  try {
    const groupId = typeof req.query.groupId === 'string' ? req.query.groupId : undefined;
    res.json({ balance: await balancesFor(req.user!.id, groupId) });
  } catch (error) {
    next(error);
  }
});

const CreateBody = z
  .object({
    /** Who paid whom. Exactly one of these is you. */
    fromUserId: z.string().uuid().optional(),
    toUserId: z.string().uuid().optional(),
    amountMinor: z.number().int().positive(),
    currency: z.string().regex(/^[A-Z]{3}$/).default('INR'),
    method: z.enum(['UPI', 'CASH', 'BANK_TRANSFER', 'CARD', 'OTHER']).default('UPI'),
    note: z.string().trim().max(500).optional(),
    groupId: z.string().uuid().optional(),
    /** Settle one specific expense rather than a running balance. */
    expenseId: z.string().uuid().optional()
  })
  .refine((value) => Boolean(value.fromUserId) !== Boolean(value.toUserId), {
    message: 'Give exactly one of fromUserId or toUserId — the other side is you.'
  });

/**
 * Records a payment. Either you paid someone (`toUserId`) or they paid you
 * (`fromUserId`), never both — the missing side is always the signed-in user, so a
 * client cannot record a transfer between two other people.
 *
 * Marks the matching shares settled, oldest first, so the money lands where the
 * debt actually is.
 */
settlementsRouter.post('/', writeLimiter, validateBody(CreateBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof CreateBody>;
    const userId = req.user!.id;

    const fromUserId = body.fromUserId ?? userId;
    const toUserId = body.toUserId ?? userId;
    if (fromUserId === toUserId) {
      throw ApiError.badRequest('SELF_SETTLEMENT', 'You cannot settle up with yourself.');
    }

    const other = await prisma.user.findUnique({
      where: { id: fromUserId === userId ? toUserId : fromUserId },
      select: publicUserFields
    });
    if (!other) throw ApiError.notFound('That person');

    const settlement = await prisma.$transaction(async (tx) => {
      const row = await tx.settlement.create({
        data: {
          ...(body.groupId ? { groupId: body.groupId } : {}),
          fromUserId,
          toUserId,
          amountMinor: body.amountMinor,
          currency: body.currency,
          method: body.method,
          status: 'CONFIRMED',
          ...(body.note ? { note: body.note } : {}),
          ...(body.expenseId ? { expenseId: body.expenseId } : {}),
          recordedById: userId,
          confirmedAt: new Date()
        },
        include: {
          fromUser: { select: publicUserFields },
          toUser: { select: publicUserFields }
        }
      });

      // Apply the money to the debtor's outstanding shares, oldest first.
      const debtorId = fromUserId;
      const creditorId = toUserId;
      const shares = await tx.expenseShare.findMany({
        where: {
          userId: debtorId,
          settledAt: null,
          expense: {
            paidById: creditorId,
            kind: 'SHARED',
            status: 'OPEN',
            ...(body.groupId ? { groupId: body.groupId } : {}),
            ...(body.expenseId ? { id: body.expenseId } : {})
          }
        },
        orderBy: { expense: { occurredAt: 'asc' } },
        select: { id: true, amountMinor: true, paidMinor: true, expenseId: true }
      });

      let remaining = body.amountMinor;
      const touchedExpenses: string[] = [];
      for (const share of shares) {
        if (remaining <= 0) break;
        const outstanding = share.amountMinor - share.paidMinor;
        if (outstanding <= 0) continue;
        const applied = Math.min(outstanding, remaining);
        await tx.expenseShare.update({
          where: { id: share.id },
          data: {
            paidMinor: share.paidMinor + applied,
            ...(applied === outstanding ? { settledAt: new Date() } : {})
          }
        });
        remaining -= applied;
        touchedExpenses.push(share.expenseId);
      }

      // An expense whose every share is settled is itself settled.
      for (const expenseId of new Set(touchedExpenses)) {
        const unsettled = await tx.expenseShare.count({ where: { expenseId, settledAt: null } });
        if (unsettled === 0) {
          await tx.expense.update({ where: { id: expenseId }, data: { status: 'SETTLED' } });
        }
      }

      await enqueueNotification(tx, {
        userId: fromUserId === userId ? toUserId : fromUserId,
        kind: 'SETTLEMENT_RECEIVED',
        title:
          fromUserId === userId
            ? `You were paid ${formatMinor(body.amountMinor, body.currency)}`
            : `A payment of ${formatMinor(body.amountMinor, body.currency)} was recorded`,
        body: body.note ?? 'Balances are up to date.',
        data: { settlementId: row.id, amountMinor: body.amountMinor }
      });

      return { row, unapplied: remaining };
    });

    res.status(201).json({
      settlement: settlement.row,
      /** Paid more than was owed — worth telling the user rather than swallowing it. */
      unappliedMinor: settlement.unapplied
    });
  } catch (error) {
    next(error);
  }
});

const ListQuery = z.object({
  groupId: z.string().uuid().optional(),
  limit: z.coerce.number().int().min(1).max(100).default(30)
});

settlementsRouter.get('/', validateQuery(ListQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof ListQuery>;
    const userId = req.user!.id;
    const settlements = await prisma.settlement.findMany({
      where: {
        ...(query.groupId ? { groupId: query.groupId } : {}),
        OR: [{ fromUserId: userId }, { toUserId: userId }]
      },
      orderBy: { createdAt: 'desc' },
      take: query.limit,
      include: {
        fromUser: { select: publicUserFields },
        toUser: { select: publicUserFields },
        group: { select: { id: true, name: true } }
      }
    });
    res.json({ settlements });
  } catch (error) {
    next(error);
  }
});

/** Undoes a settlement — the auto-matched ones especially need to be reversible. */
settlementsRouter.delete('/:id', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const userId = req.user!.id;

    const settlement = await prisma.settlement.findFirst({
      where: { id, OR: [{ fromUserId: userId }, { toUserId: userId }] },
      select: { id: true, fromUserId: true, toUserId: true, amountMinor: true, status: true }
    });
    if (!settlement) throw ApiError.notFound('That settlement');
    if (settlement.status === 'REJECTED') {
      throw ApiError.conflict('ALREADY_REVERSED', 'That settlement was already reversed.');
    }

    await prisma.$transaction(async (tx) => {
      await tx.settlement.update({ where: { id }, data: { status: 'REJECTED' } });

      // Re-open the shares it had closed, newest first.
      const shares = await tx.expenseShare.findMany({
        where: {
          userId: settlement.fromUserId,
          expense: { paidById: settlement.toUserId, kind: 'SHARED' },
          paidMinor: { gt: 0 }
        },
        orderBy: { expense: { occurredAt: 'desc' } },
        select: { id: true, paidMinor: true, expenseId: true }
      });

      let toReverse = settlement.amountMinor;
      for (const share of shares) {
        if (toReverse <= 0) break;
        const applied = Math.min(share.paidMinor, toReverse);
        await tx.expenseShare.update({
          where: { id: share.id },
          data: { paidMinor: share.paidMinor - applied, settledAt: null }
        });
        await tx.expense.update({ where: { id: share.expenseId }, data: { status: 'OPEN' } });
        toReverse -= applied;
      }

      await tx.bankAlert.updateMany({
        where: { settlementId: id },
        data: { status: 'UNMATCHED', settlementId: null }
      });
    });

    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

const RemindBody = z.object({
  userId: z.string().uuid(),
  message: z.string().trim().max(280).optional()
});

/** Nudges somebody who owes you. In-app; the worker turns it into a push. */
settlementsRouter.post('/remind', writeLimiter, validateBody(RemindBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof RemindBody>;
    const me = req.user!.id;

    const balance = await balancesFor(me);
    const person = balance.people.find((entry) => entry.userId === body.userId);
    if (!person || person.netMinor <= 0) {
      throw ApiError.badRequest('NOTHING_OWED', 'That person does not owe you anything right now.');
    }

    const meRow = await prisma.user.findUniqueOrThrow({ where: { id: me }, select: { name: true } });
    await prisma.$transaction(async (tx) => {
      await enqueueNotification(tx, {
        userId: body.userId,
        kind: 'REMINDER_TO_PAY',
        title: `${meRow.name ?? 'Someone'} is waiting on ${formatMinor(person.netMinor, balance.currency)}`,
        body: body.message ?? 'Open SnapTab to settle up.',
        data: { fromUserId: me, amountMinor: person.netMinor }
      });
    });

    res.status(202).json({ remindedMinor: person.netMinor });
  } catch (error) {
    next(error);
  }
});

function formatMinor(minor: number, currency: string): string {
  const major = (minor / 100).toFixed(2).replace(/\.00$/, '');
  return currency === 'INR' ? `₹${major}` : `${currency} ${major}`;
}
