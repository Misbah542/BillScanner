import { Router } from 'express';
import { z } from 'zod';
import { env } from '../env.js';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { randomToken } from '../lib/crypto.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams } from '../middleware/validate.js';
import { publicLimiter, writeLimiter } from '../middleware/rateLimit.js';
import { assertCanViewExpense } from '../services/expenseService.js';

/** Authenticated: creating and revoking links. */
export const shareRouter = Router();
shareRouter.use(requireAuth);

/** Unauthenticated: reading a shared tab. Mounted separately, outside requireAuth. */
export const publicShareRouter = Router();

const CreateBody = z
  .object({
    expenseId: z.string().uuid().optional(),
    groupId: z.string().uuid().optional(),
    /** SUMMARY leaves out the receipt photo. FULL includes it. */
    scope: z.enum(['SUMMARY', 'FULL']).default('SUMMARY'),
    expiresInDays: z.number().int().min(1).max(365).optional()
  })
  .refine((value) => Boolean(value.expenseId) !== Boolean(value.groupId), {
    message: 'Share either an expense or a group, not both.'
  });

/**
 * Creates a read-only link to a tab. Anyone holding it sees the items and who owes
 * what, with no account needed — which is how you settle with the one friend who
 * refuses to install anything.
 *
 * The bank alert attached to an expense is NEVER exposed through a share link, and
 * the receipt photo only when the scope says so.
 */
shareRouter.post('/', writeLimiter, validateBody(CreateBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof CreateBody>;
    const userId = req.user!.id;

    if (body.expenseId) {
      await assertCanViewExpense(body.expenseId, userId);
    } else {
      const membership = await prisma.groupMember.findUnique({
        where: { groupId_userId: { groupId: body.groupId!, userId } },
        select: { leftAt: true }
      });
      if (!membership || membership.leftAt) {
        throw ApiError.forbidden('You are not in that group.', 'NOT_A_MEMBER');
      }
    }

    const token = randomToken(12);
    const link = await prisma.shareLink.create({
      data: {
        token,
        scope: body.scope,
        ...(body.expenseId ? { expenseId: body.expenseId } : {}),
        ...(body.groupId ? { groupId: body.groupId } : {}),
        createdById: userId,
        ...(body.expiresInDays
          ? { expiresAt: new Date(Date.now() + body.expiresInDays * 86_400_000) }
          : {})
      },
      select: { id: true, token: true, scope: true, expiresAt: true, createdAt: true }
    });

    res.status(201).json({
      link: {
        ...link,
        url: `${env.APP_SHARE_BASE_URL.replace(/\/$/, '')}/${link.token}`
      }
    });
  } catch (error) {
    next(error);
  }
});

const IdParam = z.object({ id: z.string().uuid() });

shareRouter.delete('/:id', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const result = await prisma.shareLink.updateMany({
      where: { id, createdById: req.user!.id, revokedAt: null },
      data: { revokedAt: new Date() }
    });
    if (result.count === 0) throw ApiError.notFound('That link');
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

const TokenParam = z.object({ token: z.string().min(8).max(64) });

/**
 * `GET /t/:token` — the public view.
 *
 * Deliberately narrow: names and amounts, and the receipt only if the link was made
 * with FULL scope. No user ids, no email addresses, no phone numbers, no card
 * alerts, nothing that would let a leaked link identify anyone beyond a first name.
 */
publicShareRouter.get('/:token', publicLimiter, validateParams(TokenParam), async (req, res, next) => {
  try {
    const { token } = req.params as unknown as z.infer<typeof TokenParam>;

    const link = await prisma.shareLink.findUnique({
      where: { token },
      select: { id: true, scope: true, expiresAt: true, revokedAt: true, expenseId: true, groupId: true }
    });
    if (!link || link.revokedAt) throw ApiError.notFound('That link');
    if (link.expiresAt && link.expiresAt < new Date()) {
      throw new ApiError(410, 'LINK_EXPIRED', 'That link has expired. Ask for a new one.');
    }

    await prisma.shareLink.update({
      where: { id: link.id },
      data: { viewCount: { increment: 1 }, lastViewedAt: new Date() }
    });

    if (link.expenseId) {
      const expense = await prisma.expense.findUnique({
        where: { id: link.expenseId },
        select: {
          id: true,
          merchantName: true,
          occurredAt: true,
          currency: true,
          note: true,
          itemTotalMinor: true,
          serviceChargeMinor: true,
          taxMinor: true,
          discountMinor: true,
          tipMinor: true,
          roundOffMinor: true,
          totalMinor: true,
          splitMethod: true,
          receiptAssetKey: true,
          status: true,
          category: { select: { slug: true, name: true, colorHex: true, tintHex: true } },
          paidBy: { select: { name: true } },
          items: {
            orderBy: { position: 'asc' },
            select: { name: true, quantityMilli: true, amountMinor: true }
          },
          taxLines: { select: { label: true, kind: true, rateBp: true, amountMinor: true } },
          shares: {
            select: {
              amountMinor: true,
              paidMinor: true,
              settledAt: true,
              user: { select: { name: true } }
            }
          }
        }
      });
      if (!expense || expense.status === 'VOID') throw ApiError.notFound('That tab');

      res.json({
        kind: 'EXPENSE',
        scope: link.scope,
        expense: {
          merchantName: expense.merchantName,
          occurredAt: expense.occurredAt,
          currency: expense.currency,
          note: expense.note,
          category: expense.category,
          paidByName: expense.paidBy.name,
          totals: {
            itemTotalMinor: expense.itemTotalMinor,
            serviceChargeMinor: expense.serviceChargeMinor,
            taxMinor: expense.taxMinor,
            discountMinor: expense.discountMinor,
            tipMinor: expense.tipMinor,
            roundOffMinor: expense.roundOffMinor,
            totalMinor: expense.totalMinor
          },
          splitMethod: expense.splitMethod,
          items: expense.items,
          taxLines: expense.taxLines,
          shares: expense.shares.map((share) => ({
            name: share.user.name,
            amountMinor: share.amountMinor,
            paidMinor: share.paidMinor,
            settled: Boolean(share.settledAt)
          })),
          receiptUrl:
            link.scope === 'FULL' && expense.receiptAssetKey
              ? `${env.PUBLIC_BASE_URL}/t/${token}/receipt`
              : null
        }
      });
      return;
    }

    const group = await prisma.group.findUnique({
      where: { id: link.groupId! },
      select: {
        name: true,
        currency: true,
        iconKey: true,
        members: {
          where: { leftAt: null },
          select: { user: { select: { name: true } } }
        },
        expenses: {
          where: { status: { not: 'VOID' } },
          orderBy: { occurredAt: 'desc' },
          take: 50,
          select: {
            merchantName: true,
            occurredAt: true,
            totalMinor: true,
            currency: true,
            category: { select: { slug: true, name: true, colorHex: true } },
            paidBy: { select: { name: true } }
          }
        }
      }
    });
    if (!group) throw ApiError.notFound('That group');

    res.json({
      kind: 'GROUP',
      scope: link.scope,
      group: {
        name: group.name,
        currency: group.currency,
        iconKey: group.iconKey,
        memberNames: group.members.map((member) => member.user.name),
        expenses: group.expenses
      }
    });
  } catch (error) {
    next(error);
  }
});

/** The receipt image behind a FULL-scope link. */
publicShareRouter.get('/:token/receipt', publicLimiter, validateParams(TokenParam), async (req, res, next) => {
  try {
    const { token } = req.params as unknown as z.infer<typeof TokenParam>;
    const link = await prisma.shareLink.findUnique({
      where: { token },
      select: {
        scope: true,
        revokedAt: true,
        expiresAt: true,
        expense: { select: { receiptAssetKey: true, scan: { select: { mimeType: true } } } }
      }
    });
    if (!link || link.revokedAt) throw ApiError.notFound('That link');
    if (link.expiresAt && link.expiresAt < new Date()) {
      throw new ApiError(410, 'LINK_EXPIRED', 'That link has expired.');
    }
    if (link.scope !== 'FULL' || !link.expense?.receiptAssetKey) {
      throw ApiError.notFound('A receipt for that link');
    }

    const { storage } = await import('../lib/storage.js');
    const bytes = await storage.get(link.expense.receiptAssetKey);
    res.setHeader('content-type', link.expense.scan?.mimeType ?? 'image/jpeg');
    res.setHeader('cache-control', 'public, max-age=3600');
    res.send(bytes);
  } catch (error) {
    next(error);
  }
});
