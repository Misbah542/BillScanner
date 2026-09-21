import { Router } from 'express';
import { z } from 'zod';
import { CreateExpenseInput, ExpenseKind, SplitMethodSchema } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams, validateQuery } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';
import {
  applySplit,
  assertCanEditExpense,
  assertCanViewExpense,
  createExpense,
  expenseInclude
} from '../services/expenseService.js';
import { rememberMerchantCategory, resolveCategoryId, suggestCategories } from '../services/categoryService.js';
import { resolveParticipants } from '../services/userService.js';

export const expensesRouter = Router();
expensesRouter.use(requireAuth);

const IdParam = z.object({ id: z.string().uuid() });

const ListQuery = z.object({
  /** `personal` is the column the home screen shows next to shared tabs. */
  kind: z.enum(['all', 'personal', 'shared']).default('all'),
  groupId: z.string().uuid().optional(),
  categorySlug: z.string().trim().max(64).optional(),
  /** ISO dates. Inclusive `from`, exclusive `to`. */
  from: z.coerce.date().optional(),
  to: z.coerce.date().optional(),
  search: z.string().trim().max(120).optional(),
  status: z.enum(['OPEN', 'SETTLED', 'DRAFT', 'VOID']).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(25),
  cursor: z.string().uuid().optional()
});

/**
 * Everything the signed-in person can see: their own personal expenses plus any
 * shared expense they paid, created or have a share of. Keyset-paginated on
 * `occurredAt` so a long history stays cheap.
 */
expensesRouter.get('/', validateQuery(ListQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof ListQuery>;
    const userId = req.user!.id;

    const visible = {
      OR: [
        { createdById: userId },
        { paidById: userId },
        { shares: { some: { userId } } },
        { group: { members: { some: { userId, leftAt: null } } } }
      ]
    };

    const where = {
      AND: [
        visible,
        query.kind === 'all' ? {} : { kind: query.kind === 'personal' ? 'PERSONAL' as const : 'SHARED' as const },
        query.groupId ? { groupId: query.groupId } : {},
        query.categorySlug ? { category: { slug: query.categorySlug } } : {},
        query.status ? { status: query.status } : { status: { not: 'VOID' as const } },
        query.from ? { occurredAt: { gte: query.from } } : {},
        query.to ? { occurredAt: { lt: query.to } } : {},
        query.search
          ? { merchantName: { contains: query.search, mode: 'insensitive' as const } }
          : {}
      ]
    };

    const cursorRow = query.cursor
      ? await prisma.expense.findUnique({
          where: { id: query.cursor },
          select: { occurredAt: true, id: true }
        })
      : null;

    const rows = await prisma.expense.findMany({
      where: cursorRow
        ? {
            AND: [
              where,
              {
                OR: [
                  { occurredAt: { lt: cursorRow.occurredAt } },
                  { occurredAt: cursorRow.occurredAt, id: { lt: cursorRow.id } }
                ]
              }
            ]
          }
        : where,
      orderBy: [{ occurredAt: 'desc' }, { id: 'desc' }],
      take: query.limit + 1,
      include: expenseInclude
    });

    const hasMore = rows.length > query.limit;
    const page = hasMore ? rows.slice(0, query.limit) : rows;

    res.json({
      expenses: page.map((row) => serializeExpense(row, userId)),
      nextCursor: hasMore ? (page[page.length - 1]?.id ?? null) : null
    });
  } catch (error) {
    next(error);
  }
});

/**
 * Creates an expense.
 *
 * The two shapes this takes are worth spelling out, because they are the whole
 * point of the model:
 *
 *   { kind: "PERSONAL", source: "ALERT", merchantName: "SWIGGY", totalMinor: 48600 }
 *     — a lone card debit. No items, no split, counts toward monthly spend.
 *
 *   { kind: "SHARED", splitMethod: "PERCENT", participants: [...], items: [...] }
 *     — a scanned bill split four ways.
 */
expensesRouter.post('/', writeLimiter, validateBody(CreateExpenseInput), async (req, res, next) => {
  try {
    const expense = await createExpense(req.user!.id, req.body as z.infer<typeof CreateExpenseInput>);
    res.status(201).json({ expense: serializeExpense(expense, req.user!.id) });
  } catch (error) {
    next(error);
  }
});

expensesRouter.get('/:id', validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertCanViewExpense(id, req.user!.id);
    const expense = await prisma.expense.findUniqueOrThrow({ where: { id }, include: expenseInclude });
    res.json({ expense: serializeExpense(expense, req.user!.id) });
  } catch (error) {
    next(error);
  }
});

const PatchBody = z.object({
  merchantName: z.string().trim().min(1).max(200).optional(),
  note: z.string().trim().max(2000).nullable().optional(),
  occurredAt: z.coerce.date().optional(),
  categorySlug: z.string().trim().max(64).optional(),
  kind: ExpenseKind.optional(),
  groupId: z.string().uuid().nullable().optional()
});

expensesRouter.patch('/:id', writeLimiter, validateParams(IdParam), validateBody(PatchBody), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const body = req.body as z.infer<typeof PatchBody>;
    const userId = req.user!.id;
    await assertCanEditExpense(id, userId);

    const current = await prisma.expense.findUniqueOrThrow({
      where: { id },
      select: { kind: true, merchantName: true, shares: { select: { id: true } } }
    });

    if (body.kind === 'PERSONAL' && current.shares.length > 0) {
      throw ApiError.conflict(
        'HAS_SHARES',
        'Remove the split before making this a personal expense.'
      );
    }

    const categoryId = body.categorySlug ? await resolveCategoryId(body.categorySlug, userId) : undefined;

    const expense = await prisma.expense.update({
      where: { id },
      data: {
        ...(body.merchantName ? { merchantName: body.merchantName } : {}),
        ...(body.note === undefined ? {} : { note: body.note }),
        ...(body.occurredAt ? { occurredAt: body.occurredAt } : {}),
        ...(body.kind ? { kind: body.kind } : {}),
        ...(body.groupId === undefined ? {} : { groupId: body.groupId }),
        ...(categoryId ? { categoryId, categorySource: 'USER' as const, categoryConfidence: null } : {})
      },
      include: expenseInclude
    });

    // The user correcting a category is the signal worth learning from.
    if (body.categorySlug) {
      await rememberMerchantCategory(userId, body.merchantName ?? current.merchantName, categoryId ?? null);
    }

    res.json({ expense: serializeExpense(expense, userId) });
  } catch (error) {
    next(error);
  }
});

/** Soft delete: the row stays so balances and history stay explicable. */
expensesRouter.delete('/:id', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertCanEditExpense(id, req.user!.id);
    await prisma.expense.update({ where: { id }, data: { status: 'VOID' } });
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

const SplitBody = z.object({
  method: SplitMethodSchema,
  participants: z
    .array(
      z.object({
        userId: z.string().uuid().optional(),
        email: z.string().trim().email().optional(),
        phone: z.string().trim().max(24).optional(),
        displayName: z.string().trim().max(120).optional(),
        value: z.number().int().min(0).optional()
      })
    )
    .max(60)
    .default([]),
  itemAssignments: z
    .array(
      z.object({
        itemId: z.string().uuid(),
        assignments: z
          .array(z.object({ userId: z.string().uuid(), weight: z.number().int().min(0).max(1000).default(1) }))
          .min(1)
      })
    )
    .default([]),
  extrasMode: z.enum(['PROPORTIONAL', 'EQUAL']).default('PROPORTIONAL')
});

/**
 * Sets or replaces the split. Recomputes from scratch every time — the engine then
 * guarantees the shares still add up to the exact total, which patching could not.
 *
 * Turning a personal expense into a shared one is just a call to this.
 */
expensesRouter.put('/:id/split', writeLimiter, validateParams(IdParam), validateBody(SplitBody), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const body = req.body as z.infer<typeof SplitBody>;
    const userId = req.user!.id;
    await assertCanEditExpense(id, userId);

    const expense = await prisma.expense.findUniqueOrThrow({
      where: { id },
      select: {
        totalMinor: true,
        paidById: true,
        serviceChargeMinor: true,
        taxMinor: true,
        tipMinor: true,
        roundOffMinor: true,
        discountMinor: true,
        items: { select: { id: true, amountMinor: true }, orderBy: { position: 'asc' } }
      }
    });

    if (body.method === 'ITEMIZED' && body.itemAssignments.length === 0) {
      throw ApiError.badRequest('NO_ASSIGNMENTS', 'Assign at least one item to somebody.');
    }
    if (body.method !== 'ITEMIZED' && body.participants.length === 0) {
      throw ApiError.badRequest('NO_PARTICIPANTS', 'Add at least one person to split with.');
    }

    const participants = await resolveParticipants(body.participants);

    const extrasMinor =
      expense.serviceChargeMinor +
      expense.taxMinor +
      expense.tipMinor +
      expense.roundOffMinor -
      expense.discountMinor;

    const updated = await prisma.$transaction(async (tx) => {
      await applySplit(tx, {
        expenseId: id,
        payerId: expense.paidById,
        totalMinor: expense.totalMinor,
        extrasMinor,
        extrasMode: body.extrasMode,
        method: body.method,
        participants,
        items: expense.items,
        itemAssignments: body.itemAssignments,
        inputItems: []
      });
      await tx.expense.update({ where: { id }, data: { kind: 'SHARED' } });
      return tx.expense.findUniqueOrThrow({ where: { id }, include: expenseInclude });
    });

    res.json({ expense: serializeExpense(updated, userId) });
  } catch (error) {
    next(error);
  }
});

/** Drops the split and makes it personal again. */
expensesRouter.delete('/:id/split', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    await assertCanEditExpense(id, req.user!.id);

    const updated = await prisma.$transaction(async (tx) => {
      await tx.expenseItemShare.deleteMany({ where: { expenseItem: { expenseId: id } } });
      await tx.expenseShare.deleteMany({ where: { expenseId: id } });
      await tx.expense.update({
        where: { id },
        data: { kind: 'PERSONAL', splitMethod: null, groupId: null }
      });
      return tx.expense.findUniqueOrThrow({ where: { id }, include: expenseInclude });
    });

    res.json({ expense: serializeExpense(updated, req.user!.id) });
  } catch (error) {
    next(error);
  }
});

const SuggestQuery = z.object({
  merchant: z.string().trim().max(200).optional(),
  items: z.string().trim().max(2000).optional(),
  direction: z.enum(['DEBIT', 'CREDIT']).optional()
});

/** Category suggestions for a merchant the user is typing, before anything is saved. */
expensesRouter.get('/suggest/category', validateQuery(SuggestQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof SuggestQuery>;
    const suggestions = await suggestCategories(req.user!.id, {
      merchant: query.merchant ?? null,
      itemNames: query.items ? query.items.split('|').filter(Boolean) : [],
      ...(query.direction ? { direction: query.direction } : {})
    });
    res.json({ suggestions });
  } catch (error) {
    next(error);
  }
});

type ExpenseRow = Awaited<ReturnType<typeof createExpense>>;

/**
 * One shape for an expense on the wire, with the two figures the UI actually
 * displays computed here rather than in the client: `yourShareMinor` (what this
 * costs you) and `youAreOwedMinor` (what you fronted for other people).
 */
export function serializeExpense(expense: ExpenseRow, viewerId: string) {
  const yourShare = expense.shares.find((share) => share.userId === viewerId);
  const yourShareMinor =
    expense.kind === 'PERSONAL'
      ? expense.paidById === viewerId
        ? expense.totalMinor
        : 0
      : (yourShare?.amountMinor ?? 0);

  const outstandingFromOthers = expense.shares
    .filter((share) => share.userId !== viewerId && !share.settledAt)
    .reduce((total, share) => total + share.amountMinor - share.paidMinor, 0);

  return {
    id: expense.id,
    kind: expense.kind,
    source: expense.source,
    status: expense.status,
    merchantName: expense.merchantName,
    note: expense.note,
    occurredAt: expense.occurredAt,
    currency: expense.currency,
    category: expense.category,
    categorySource: expense.categorySource,
    categoryConfidence: expense.categoryConfidence,
    group: expense.group,
    paidBy: expense.paidBy,
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
    extrasMode: expense.extrasMode,
    items: expense.items.map((item) => ({
      id: item.id,
      name: item.name,
      quantityMilli: item.quantityMilli,
      unitPriceMinor: item.unitPriceMinor,
      amountMinor: item.amountMinor,
      assignedTo: item.shares.map((share) => ({
        userId: share.userId,
        weight: share.weight,
        amountMinor: share.amountMinor
      }))
    })),
    taxLines: expense.taxLines.map((line) => ({
      label: line.label,
      kind: line.kind,
      rateBp: line.rateBp,
      amountMinor: line.amountMinor
    })),
    shares: expense.shares.map((share) => ({
      userId: share.userId,
      user: share.user,
      amountMinor: share.amountMinor,
      paidMinor: share.paidMinor,
      weightBp: share.weightBp,
      inputValue: share.inputValue,
      settledAt: share.settledAt
    })),
    matchedAlerts: expense.alerts,
    viewer: {
      isPayer: expense.paidById === viewerId,
      yourShareMinor,
      youAreOwedMinor: expense.paidById === viewerId ? outstandingFromOthers : 0,
      youOweMinor:
        expense.paidById === viewerId || !yourShare
          ? 0
          : yourShare.amountMinor - yourShare.paidMinor
    },
    hasReceipt: Boolean(expense.receiptAssetKey),
    createdAt: expense.createdAt,
    updatedAt: expense.updatedAt
  };
}
