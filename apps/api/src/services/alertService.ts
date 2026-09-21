import type { IngestAlertInput } from '@snaptab/shared';
import { normalizeMerchant } from '@snaptab/shared';
import type { Prisma } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { resolveCategoryId, suggestCategories } from './categoryService.js';
import { enqueueNotification } from './notificationService.js';

export interface IngestOutcome {
  alertId: string;
  /** False when this exact alert was already recorded — the phone may re-read its inbox. */
  created: boolean;
  status: 'UNMATCHED' | 'LINKED' | 'SETTLED' | 'IGNORED';
  /** Set when the credit looks like someone paying you back. */
  matchedSettlementFor?: { userId: string; name: string | null; amountMinor: number };
  suggestedCategorySlug?: string;
}

/**
 * Takes alerts the device parsed and files them.
 *
 * Three things happen per alert: de-duplicate on the fingerprint, guess a
 * category, and — for credits — see whether it settles something someone owes. A
 * debit is left `UNMATCHED` in the inbox, because only the user knows whether a
 * ₹486 Swiggy charge is a personal expense or the start of a tab to split.
 */
export async function ingestAlerts(
  userId: string,
  alerts: readonly IngestAlertInput[]
): Promise<IngestOutcome[]> {
  const user = await prisma.user.findUniqueOrThrow({
    where: { id: userId },
    select: { keepAlertBodies: true }
  });

  const outcomes: IngestOutcome[] = [];

  for (const alert of alerts) {
    const existing = await prisma.bankAlert.findUnique({
      where: { userId_fingerprint: { userId, fingerprint: alert.fingerprint } },
      select: { id: true, status: true }
    });
    if (existing) {
      outcomes.push({ alertId: existing.id, created: false, status: existing.status });
      continue;
    }

    const [suggestion] = await suggestCategories(
      userId,
      {
        merchant: alert.merchantRaw ?? null,
        alertText: alert.rawBody ?? null,
        direction: alert.direction
      },
      1
    );
    const suggestedCategoryId = suggestion?.slug
      ? await resolveCategoryId(suggestion.slug, userId)
      : null;

    const settlement =
      alert.direction === 'CREDIT' ? await findSettlementCandidate(userId, alert) : null;

    const created = await prisma.$transaction(async (tx) => {
      const row = await tx.bankAlert.create({
        data: {
          userId,
          direction: alert.direction,
          channel: alert.channel,
          status: settlement ? 'SETTLED' : 'UNMATCHED',
          amountMinor: alert.amountMinor,
          currency: alert.currency,
          ...(alert.merchantRaw
            ? {
                merchantRaw: alert.merchantRaw,
                merchantNormalized: normalizeMerchant(alert.merchantRaw)
              }
            : {}),
          ...(alert.accountMask ? { accountMask: alert.accountMask } : {}),
          accountKind: alert.accountKind,
          ...(alert.bankId ? { bankId: alert.bankId } : {}),
          ...(alert.bankName ? { bankName: alert.bankName } : {}),
          ...(alert.referenceNumber ? { referenceNumber: alert.referenceNumber } : {}),
          fingerprint: alert.fingerprint,
          confidence: alert.confidence,
          // The message body is only ever kept if the user turned that on.
          ...(user.keepAlertBodies && alert.rawBody ? { rawBody: alert.rawBody } : {}),
          ...(suggestedCategoryId ? { suggestedCategoryId } : {}),
          occurredAt: alert.occurredAt
        },
        select: { id: true, status: true }
      });

      if (settlement) {
        await tx.bankAlert.update({
          where: { id: row.id },
          data: { settlementId: settlement.settlementId }
        });
      } else if (alert.direction === 'DEBIT') {
        // This is the notification the user sees when the app is closed, with
        // "Add to a tab" / "Scan bill" / "Ignore" actions.
        await enqueueNotification(tx, {
          userId,
          kind: 'ALERT_NEEDS_EXPENSE',
          title: `${formatMinor(alert.amountMinor, alert.currency)} at ${alert.merchantRaw ?? 'an unknown merchant'}`,
          body: suggestion
            ? `Log it as ${suggestion.name}, or scan the bill to split it.`
            : 'Log it as an expense, or scan the bill to split it.',
          data: {
            alertId: row.id,
            amountMinor: alert.amountMinor,
            merchant: alert.merchantRaw ?? null,
            suggestedCategorySlug: suggestion?.slug ?? null
          }
        });
      }

      return row;
    });

    outcomes.push({
      alertId: created.id,
      created: true,
      status: created.status,
      ...(settlement
        ? {
            matchedSettlementFor: {
              userId: settlement.fromUserId,
              name: settlement.fromName,
              amountMinor: alert.amountMinor
            }
          }
        : {}),
      ...(suggestion ? { suggestedCategorySlug: suggestion.slug } : {})
    });
  }

  return outcomes;
}

/**
 * A credit for exactly what someone owes you, within a couple of days, is almost
 * always them settling up. We record the settlement and tell the user rather than
 * asking — with `AUTO` provenance, so it can be undone.
 */
async function findSettlementCandidate(
  userId: string,
  alert: IngestAlertInput
): Promise<{ settlementId: string; fromUserId: string; fromName: string | null } | null> {
  const debtors = await prisma.expenseShare.findMany({
    where: {
      userId: { not: userId },
      settledAt: null,
      expense: { paidById: userId, kind: 'SHARED', status: 'OPEN' }
    },
    select: {
      id: true,
      userId: true,
      amountMinor: true,
      paidMinor: true,
      expenseId: true,
      expense: { select: { groupId: true, currency: true } },
      user: { select: { id: true, name: true } }
    }
  });

  const owed = new Map<string, { outstanding: number; name: string | null; shareIds: string[]; groupId: string | null }>();
  for (const share of debtors) {
    const entry = owed.get(share.userId) ?? {
      outstanding: 0,
      name: share.user.name,
      shareIds: [],
      groupId: share.expense.groupId
    };
    entry.outstanding += share.amountMinor - share.paidMinor;
    entry.shareIds.push(share.id);
    owed.set(share.userId, entry);
  }

  const match = [...owed.entries()].find(([, entry]) => entry.outstanding === alert.amountMinor);
  if (!match) return null;
  const [fromUserId, entry] = match;

  const settlement = await prisma.$transaction(async (tx) => {
    const row = await tx.settlement.create({
      data: {
        ...(entry.groupId ? { groupId: entry.groupId } : {}),
        fromUserId,
        toUserId: userId,
        amountMinor: alert.amountMinor,
        currency: alert.currency,
        method: 'UPI',
        status: 'CONFIRMED',
        note: 'Matched automatically from a credit alert',
        recordedById: userId,
        confirmedAt: new Date()
      },
      select: { id: true }
    });

    await tx.expenseShare.updateMany({
      where: { id: { in: entry.shareIds } },
      data: { settledAt: new Date() }
    });
    for (const shareId of entry.shareIds) {
      const share = debtors.find((s) => s.id === shareId)!;
      await tx.expenseShare.update({
        where: { id: shareId },
        data: { paidMinor: share.amountMinor }
      });
    }

    await enqueueNotification(tx, {
      userId,
      kind: 'SETTLEMENT_RECEIVED',
      title: `${entry.name ?? 'Someone'} paid you ${formatMinor(alert.amountMinor, alert.currency)}`,
      body: 'SnapTab spotted the credit and closed it out.',
      data: { settlementId: row.id, fromUserId }
    });

    return row;
  });

  return { settlementId: settlement.id, fromUserId, fromName: entry.name };
}

export const alertSelect = {
  id: true,
  direction: true,
  channel: true,
  status: true,
  amountMinor: true,
  currency: true,
  merchantRaw: true,
  merchantNormalized: true,
  accountMask: true,
  accountKind: true,
  bankName: true,
  referenceNumber: true,
  confidence: true,
  occurredAt: true,
  createdAt: true,
  expenseId: true,
  settlementId: true,
  suggestedCategory: { select: { slug: true, name: true, colorHex: true, tintHex: true } }
} satisfies Prisma.BankAlertSelect;

function formatMinor(minor: number, currency: string): string {
  const major = (minor / 100).toFixed(2).replace(/\.00$/, '');
  return currency === 'INR' ? `₹${major}` : `${currency} ${major}`;
}
