import type { Prisma, PrismaClient } from '@prisma/client';
import {
  type CreateExpenseInput,
  computeItemizedSplit,
  computeSplit,
  normalizeMerchant
} from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { rememberMerchantCategory, resolveCategoryId, suggestCategories } from './categoryService.js';
import { resolveParticipants } from './userService.js';
import { enqueueNotification } from './notificationService.js';

type Db = Prisma.TransactionClient;

export const expenseInclude = {
  category: { select: { id: true, slug: true, name: true, colorHex: true, tintHex: true, kind: true } },
  group: { select: { id: true, name: true, iconKey: true, currency: true } },
  paidBy: { select: { id: true, name: true, avatarUrl: true } },
  items: {
    orderBy: { position: 'asc' },
    include: {
      shares: {
        // Deterministic, for the same reason the shares below are.
        orderBy: { userId: 'asc' },
        select: { userId: true, weight: true, amountMinor: true }
      }
    }
  },
  taxLines: true,
  shares: {
    /**
     * Biggest share first, then by id to break a tie.
     *
     * Without an order Postgres returns these in whatever order it finds them, which is
     * not stable between requests — so the list of people in a split could reshuffle on
     * a refresh, with no change to the data. Biggest first is also the useful order to
     * read: it answers "who owes the most" without scanning.
     */
    orderBy: [{ amountMinor: 'desc' }, { userId: 'asc' }],
    include: { user: { select: { id: true, name: true, avatarUrl: true, status: true } } }
  },
  alerts: {
    select: { id: true, accountMask: true, bankName: true, amountMinor: true, occurredAt: true, direction: true }
  }
} satisfies Prisma.ExpenseInclude;

/**
 * Creates an expense.
 *
 * PERSONAL is the easy, common case: an amount and a merchant, often straight off
 * a bank alert, with no items and no shares at all. SHARED adds the split, which
 * is computed by the shared engine so the API and the app can never disagree about
 * who owes what.
 */
export async function createExpense(
  userId: string,
  input: CreateExpenseInput
): Promise<Prisma.ExpenseGetPayload<{ include: typeof expenseInclude }>> {
  const payerId = input.paidByUserId ?? userId;

  if (input.groupId) await assertGroupMember(input.groupId, userId, prisma);
  if (input.kind === 'PERSONAL' && payerId !== userId) {
    throw ApiError.badRequest(
      'PERSONAL_EXPENSE_PAYER',
      'A personal expense is always paid by you. Share it to record someone else paying.'
    );
  }

  const itemsSum = input.items.reduce((total, item) => total + item.amountMinor, 0);
  const itemTotalMinor = input.itemTotalMinor ?? (itemsSum > 0 ? itemsSum : input.totalMinor);

  // Everything that is not a line item, which is what an itemised split spreads.
  const extrasMinor =
    input.serviceChargeMinor + input.taxMinor + input.tipMinor + input.roundOffMinor - input.discountMinor;

  const categoryIdFromSlug = input.categorySlug
    ? await resolveCategoryId(input.categorySlug, userId)
    : null;

  // No category given: take the classifier's best guess and mark it as a guess,
  // so the UI can show it as a suggestion the user can overrule.
  let categoryId = categoryIdFromSlug;
  let categorySource: 'USER' | 'SUGGESTED' | 'DEFAULT' = input.categorySlug ? 'USER' : 'DEFAULT';
  let categoryConfidence: number | null = null;

  if (!categoryId && input.merchantName) {
    const [best] = await suggestCategories(userId, {
      merchant: input.merchantName,
      itemNames: input.items.map((item) => item.name)
    });
    if (best && best.confidence > 0.45) {
      categoryId = await resolveCategoryId(best.slug, userId);
      categorySource = 'SUGGESTED';
      categoryConfidence = best.confidence;
    }
  }

  const resolvedParticipants =
    input.kind === 'SHARED' && input.participants?.length
      ? await resolveParticipants(input.participants)
      : [];

  return prisma.$transaction(async (tx) => {
    const expense = await tx.expense.create({
      data: {
        kind: input.kind,
        source: input.source,
        status: 'OPEN',
        ...(input.groupId ? { groupId: input.groupId } : {}),
        createdById: userId,
        paidById: payerId,
        ...(input.merchantName
          ? { merchantName: input.merchantName, merchantNormalized: normalizeMerchant(input.merchantName) }
          : {}),
        ...(input.note ? { note: input.note } : {}),
        occurredAt: input.occurredAt ?? new Date(),
        currency: input.currency,
        ...(categoryId ? { categoryId } : {}),
        categorySource,
        ...(categoryConfidence === null ? {} : { categoryConfidence }),
        itemTotalMinor,
        serviceChargeMinor: input.serviceChargeMinor,
        taxMinor: input.taxMinor,
        discountMinor: input.discountMinor,
        tipMinor: input.tipMinor,
        roundOffMinor: input.roundOffMinor,
        totalMinor: input.totalMinor,
        ...(input.kind === 'SHARED' && input.splitMethod ? { splitMethod: input.splitMethod } : {}),
        extrasMode: input.extrasMode,
        ...(input.scanId ? { scanId: input.scanId } : {}),
        ...(input.receiptAssetKey ? { receiptAssetKey: input.receiptAssetKey } : {}),
        items: {
          create: input.items.map((item, index) => ({
            position: index,
            name: item.name,
            quantityMilli: item.quantityMilli,
            unitPriceMinor: item.unitPriceMinor,
            amountMinor: item.amountMinor
          }))
        },
        taxLines: {
          create: input.taxLines.map((line) => ({
            label: line.label,
            kind: line.kind,
            ...(line.rateBp === undefined ? {} : { rateBp: line.rateBp }),
            amountMinor: line.amountMinor
          }))
        }
      },
      include: { items: { orderBy: { position: 'asc' } } }
    });

    if (input.kind === 'SHARED') {
      await applySplit(tx, {
        expenseId: expense.id,
        payerId,
        totalMinor: input.totalMinor,
        extrasMinor,
        extrasMode: input.extrasMode,
        method: input.splitMethod!,
        participants: resolvedParticipants,
        items: expense.items.map((item) => ({ id: item.id, amountMinor: item.amountMinor })),
        itemAssignments: input.itemAssignments ?? [],
        inputItems: input.items
      });
    }

    if (input.alertId) {
      await tx.bankAlert.updateMany({
        where: { id: input.alertId, userId },
        data: { status: 'LINKED', expenseId: expense.id }
      });
    }

    if (input.categorySlug) {
      await rememberMerchantCategory(userId, input.merchantName, categoryId, tx);
    }

    const full = await tx.expense.findUniqueOrThrow({
      where: { id: expense.id },
      include: expenseInclude
    });

    // Tell everyone except the payer that they are on a new tab.
    for (const share of full.shares) {
      if (share.userId === payerId) continue;
      await enqueueNotification(tx, {
        userId: share.userId,
        kind: 'SHARE_ASSIGNED',
        title: `${full.merchantName ?? 'A new tab'}`,
        body: `You owe ${formatShare(share.amountMinor, full.currency)} of ${formatShare(full.totalMinor, full.currency)}.`,
        data: { expenseId: full.id, amountMinor: share.amountMinor }
      });
    }

    return full;
  });
}

interface ApplySplitArgs {
  expenseId: string;
  payerId: string;
  totalMinor: number;
  extrasMinor: number;
  extrasMode: 'PROPORTIONAL' | 'EQUAL';
  method: 'EQUAL' | 'EXACT' | 'PERCENT' | 'SHARES' | 'ITEMIZED';
  participants: Array<{ userId: string; value?: number }>;
  items: Array<{ id: string; amountMinor: number }>;
  itemAssignments: ReadonlyArray<{ itemId: string; assignments: ReadonlyArray<{ userId: string; weight: number }> }>;
  inputItems: CreateExpenseInput['items'];
}

/**
 * Replaces an expense's shares from scratch. Used both on create and whenever the
 * split is edited — recomputing is always safer than patching, and the engine
 * guarantees the shares still sum to the total.
 */
export async function applySplit(tx: Db, args: ApplySplitArgs): Promise<void> {
  await tx.expenseItemShare.deleteMany({ where: { expenseItem: { expenseId: args.expenseId } } });
  await tx.expenseShare.deleteMany({ where: { expenseId: args.expenseId } });

  if (args.method === 'ITEMIZED') {
    // Item assignments arrive keyed by the *input* item order when the caller
    // created the items in the same request, so map positions onto real ids.
    const byIndex = new Map(args.items.map((item, index) => [String(index), item.id]));
    const lines = args.itemAssignments.map((assignment) => {
      const itemId = args.items.some((i) => i.id === assignment.itemId)
        ? assignment.itemId
        : byIndex.get(assignment.itemId);
      if (!itemId) {
        throw ApiError.badRequest('UNKNOWN_ITEM', `No such item on this expense: ${assignment.itemId}.`);
      }
      const item = args.items.find((i) => i.id === itemId)!;
      return {
        itemId,
        amountMinor: item.amountMinor,
        assignments: assignment.assignments.map((a) => ({ userId: a.userId, weight: a.weight }))
      };
    });

    const result = computeItemizedSplit({
      items: lines,
      extrasMinor: args.extrasMinor,
      extrasMode: args.extrasMode,
      participantIds: args.participants.map((p) => p.userId),
      payerUserId: args.payerId
    });

    for (const line of result.perItem) {
      const weights = lines.find((l) => l.itemId === line.itemId)?.assignments ?? [];
      await tx.expenseItemShare.createMany({
        data: line.shares.map((share) => ({
          expenseItemId: line.itemId,
          userId: share.userId,
          weight: weights.find((w) => w.userId === share.userId)?.weight ?? 1,
          amountMinor: share.amountMinor
        }))
      });
    }

    await writeShares(tx, args.expenseId, args.payerId, result.shares);
    await tx.expense.update({
      where: { id: args.expenseId },
      data: { splitMethod: 'ITEMIZED', extrasMode: args.extrasMode }
    });
    return;
  }

  const result = computeSplit({
    method: args.method,
    totalMinor: args.totalMinor,
    participants: args.participants.map((p) => ({
      userId: p.userId,
      ...(p.value === undefined ? {} : { value: p.value })
    })),
    payerUserId: args.payerId
  });

  await writeShares(tx, args.expenseId, args.payerId, result.shares);
  await tx.expense.update({ where: { id: args.expenseId }, data: { splitMethod: args.method } });
}

async function writeShares(
  tx: Db,
  expenseId: string,
  payerId: string,
  shares: ReadonlyArray<{ userId: string; amountMinor: number; weightBp: number; inputValue?: number }>
): Promise<void> {
  await tx.expenseShare.createMany({
    data: shares.map((share) => ({
      expenseId,
      userId: share.userId,
      amountMinor: share.amountMinor,
      // The payer has already paid their own share at the till.
      paidMinor: share.userId === payerId ? share.amountMinor : 0,
      weightBp: share.weightBp,
      ...(share.inputValue === undefined ? {} : { inputValue: share.inputValue }),
      ...(share.userId === payerId ? { settledAt: new Date() } : {})
    }))
  });
}

export async function assertGroupMember(
  groupId: string,
  userId: string,
  db: PrismaClient | Db
): Promise<void> {
  const membership = await db.groupMember.findUnique({
    where: { groupId_userId: { groupId, userId } },
    select: { leftAt: true }
  });
  if (!membership || membership.leftAt) {
    throw ApiError.forbidden('You are not in that group.', 'NOT_A_MEMBER');
  }
}

/**
 * Who may see an expense: whoever created it, whoever paid, anyone with a share,
 * and any member of the group it belongs to.
 */
export async function assertCanViewExpense(expenseId: string, userId: string): Promise<void> {
  const expense = await prisma.expense.findUnique({
    where: { id: expenseId },
    select: {
      createdById: true,
      paidById: true,
      groupId: true,
      shares: { select: { userId: true } }
    }
  });
  if (!expense) throw ApiError.notFound('That expense');

  if (
    expense.createdById === userId ||
    expense.paidById === userId ||
    expense.shares.some((share) => share.userId === userId)
  ) {
    return;
  }
  if (expense.groupId) {
    await assertGroupMember(expense.groupId, userId, prisma);
    return;
  }
  throw ApiError.forbidden('That expense is not yours.', 'NOT_YOUR_EXPENSE');
}

/** Only the person who created an expense, or who paid it, may change or delete it. */
export async function assertCanEditExpense(expenseId: string, userId: string): Promise<void> {
  const expense = await prisma.expense.findUnique({
    where: { id: expenseId },
    select: { createdById: true, paidById: true, status: true }
  });
  if (!expense) throw ApiError.notFound('That expense');
  if (expense.status === 'VOID') {
    throw ApiError.conflict('EXPENSE_VOID', 'That expense was deleted.');
  }
  if (expense.createdById !== userId && expense.paidById !== userId) {
    throw ApiError.forbidden(
      'Only the person who added this expense, or who paid it, can change it.',
      'NOT_EXPENSE_OWNER'
    );
  }
}

function formatShare(minor: number, currency: string): string {
  const major = (minor / 100).toFixed(2);
  return currency === 'INR' ? `₹${major}` : `${currency} ${major}`;
}
