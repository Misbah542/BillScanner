import { minimalTransfers } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';

export interface PersonBalance {
  userId: string;
  name: string | null;
  avatarUrl: string | null;
  status: string;
  /** Positive: they owe you. Negative: you owe them. */
  netMinor: number;
}

export interface BalanceReport {
  currency: string;
  owedToYouMinor: number;
  owedByYouMinor: number;
  netMinor: number;
  people: PersonBalance[];
  /** The fewest transfers that would clear everything. */
  suggestedTransfers: Array<{
    fromUserId: string;
    toUserId: string;
    amountMinor: number;
    fromName: string | null;
    toName: string | null;
  }>;
}

/**
 * Works out who owes whom.
 *
 * Expense shares are the ONLY source of truth: each share carries `amountMinor`
 * (owed) and `paidMinor` (put in so far), and every settlement — recorded by hand
 * or matched automatically from a credit alert — is applied to those shares when it
 * is created, and unwound from them when it is reversed. Counting settlements
 * separately as well would subtract the same payment twice.
 *
 * Scoped to one group when `groupId` is given, otherwise across everything.
 */
export async function balancesFor(userId: string, groupId?: string): Promise<BalanceReport> {
  const scope = groupId ? { groupId } : {};

  const [sharesOnMyExpenses, myShares] = await Promise.all([
    // Other people's shares of expenses I paid for — what they owe me.
    prisma.expenseShare.findMany({
      where: {
        userId: { not: userId },
        expense: { ...scope, paidById: userId, status: { in: ['OPEN', 'SETTLED'] }, kind: 'SHARED' }
      },
      select: {
        userId: true,
        amountMinor: true,
        paidMinor: true,
        user: { select: { id: true, name: true, avatarUrl: true, status: true } }
      }
    }),
    // My share of expenses other people paid for — what I owe them.
    prisma.expenseShare.findMany({
      where: {
        userId,
        expense: { ...scope, paidById: { not: userId }, status: { in: ['OPEN', 'SETTLED'] }, kind: 'SHARED' }
      },
      select: {
        amountMinor: true,
        paidMinor: true,
        expense: {
          select: { paidBy: { select: { id: true, name: true, avatarUrl: true, status: true } } }
        }
      }
    })
  ]);

  const net = new Map<string, PersonBalance>();
  const touch = (person: { id: string; name: string | null; avatarUrl: string | null; status: string }) => {
    if (!net.has(person.id)) {
      net.set(person.id, {
        userId: person.id,
        name: person.name,
        avatarUrl: person.avatarUrl,
        status: person.status,
        netMinor: 0
      });
    }
    return net.get(person.id)!;
  };

  for (const share of sharesOnMyExpenses) {
    touch(share.user).netMinor += share.amountMinor - share.paidMinor;
  }
  for (const share of myShares) {
    touch(share.expense.paidBy).netMinor -= share.amountMinor - share.paidMinor;
  }

  const people = [...net.values()]
    .filter((person) => person.netMinor !== 0)
    .sort((a, b) => b.netMinor - a.netMinor);

  const owedToYouMinor = people.filter((p) => p.netMinor > 0).reduce((a, p) => a + p.netMinor, 0);
  const owedByYouMinor = people.filter((p) => p.netMinor < 0).reduce((a, p) => a - p.netMinor, 0);

  // From my point of view: someone with a positive balance owes me.
  const transferInput: Record<string, number> = { [userId]: owedToYouMinor - owedByYouMinor };
  for (const person of people) transferInput[person.userId] = -person.netMinor;

  const names = new Map(people.map((p) => [p.userId, p.name]));
  const suggestedTransfers = minimalTransfers(transferInput).map((transfer) => ({
    ...transfer,
    fromName: transfer.fromUserId === userId ? 'You' : (names.get(transfer.fromUserId) ?? null),
    toName: transfer.toUserId === userId ? 'You' : (names.get(transfer.toUserId) ?? null)
  }));

  const currency = groupId
    ? ((await prisma.group.findUnique({ where: { id: groupId }, select: { currency: true } }))?.currency ?? 'INR')
    : ((await prisma.user.findUnique({ where: { id: userId }, select: { currency: true } }))?.currency ?? 'INR');

  return {
    currency,
    owedToYouMinor,
    owedByYouMinor,
    netMinor: owedToYouMinor - owedByYouMinor,
    people,
    suggestedTransfers
  };
}
