import { apportion, sumMinor } from './money.js';

export type SplitMethod = 'EQUAL' | 'EXACT' | 'PERCENT' | 'SHARES' | 'ITEMIZED';

/** Basis points: 10 000 bp = 100%. Percentages are never stored as floats. */
export const BP_TOTAL = 10_000;

export class SplitError extends Error {
  constructor(
    readonly code:
      | 'NO_PARTICIPANTS'
      | 'EXACT_SUM_MISMATCH'
      | 'PERCENT_SUM_MISMATCH'
      | 'SHARES_ALL_ZERO'
      | 'NEGATIVE_VALUE'
      | 'DUPLICATE_PARTICIPANT'
      | 'ITEM_UNASSIGNED'
      | 'MISSING_VALUE',
    message: string,
    readonly detail?: Record<string, unknown>
  ) {
    super(message);
    this.name = 'SplitError';
  }
}

export interface SplitParticipant {
  userId: string;
  /**
   * Meaning depends on the method:
   *  - `EXACT`   minor units this person owes
   *  - `PERCENT` basis points (2 500 = 25%)
   *  - `SHARES`  share count (2 = pays twice what 1 pays)
   *  - `EQUAL`   ignored
   */
  value?: number;
}

export interface SplitInput {
  method: Exclude<SplitMethod, 'ITEMIZED'>;
  totalMinor: number;
  participants: readonly SplitParticipant[];
  /** Absorbs the odd minor units left over by the division. Defaults to the first participant. */
  payerUserId?: string;
}

export interface SplitShare {
  userId: string;
  amountMinor: number;
  /** This person's slice of the total, in basis points — for display and for audit. */
  weightBp: number;
  /** The raw input value kept alongside the result, so a saved split can be re-edited. */
  inputValue?: number;
}

export interface SplitResult {
  method: SplitMethod;
  totalMinor: number;
  shares: SplitShare[];
}

/**
 * The one function every caller uses. Whatever the method, the guarantee is the
 * same: `sum(shares) === totalMinor`, exactly, with no share negative.
 */
export function computeSplit(input: SplitInput): SplitResult {
  const { method, totalMinor, participants } = input;

  if (participants.length === 0) {
    throw new SplitError('NO_PARTICIPANTS', 'a split needs at least one participant');
  }
  const seen = new Set<string>();
  for (const p of participants) {
    if (seen.has(p.userId)) {
      throw new SplitError('DUPLICATE_PARTICIPANT', `${p.userId} appears twice in the split`, {
        userId: p.userId
      });
    }
    seen.add(p.userId);
  }

  const preferIndex = Math.max(
    0,
    participants.findIndex((p) => p.userId === input.payerUserId)
  );

  let amounts: number[];
  switch (method) {
    case 'EQUAL': {
      amounts = apportion(totalMinor, participants.map(() => 1), preferIndex);
      break;
    }
    case 'EXACT': {
      const values = requireValues(participants, 'EXACT');
      const sum = sumMinor(values);
      if (sum !== totalMinor) {
        throw new SplitError(
          'EXACT_SUM_MISMATCH',
          `the amounts add up to ${sum} but the bill is ${totalMinor}`,
          { sum, totalMinor, differenceMinor: totalMinor - sum }
        );
      }
      amounts = values;
      break;
    }
    case 'PERCENT': {
      const values = requireValues(participants, 'PERCENT');
      const sum = values.reduce((a, b) => a + b, 0);
      if (sum !== BP_TOTAL) {
        throw new SplitError(
          'PERCENT_SUM_MISMATCH',
          `the percentages add up to ${sum / 100}% instead of 100%`,
          { sumBp: sum, differenceBp: BP_TOTAL - sum }
        );
      }
      amounts = apportion(totalMinor, values, preferIndex);
      break;
    }
    case 'SHARES': {
      const values = requireValues(participants, 'SHARES');
      if (values.every((v) => v === 0)) {
        throw new SplitError('SHARES_ALL_ZERO', 'at least one person needs a share');
      }
      amounts = apportion(totalMinor, values, preferIndex);
      break;
    }
  }

  return {
    method,
    totalMinor,
    shares: participants.map((p, i) => ({
      userId: p.userId,
      amountMinor: amounts[i]!,
      weightBp: bpOf(amounts[i]!, totalMinor),
      ...(p.value === undefined ? {} : { inputValue: p.value })
    }))
  };
}

export interface ItemizedAssignment {
  userId: string;
  /** Relative weight on this one item. Two people at weight 1 each split it in half. */
  weight?: number;
}

export interface ItemizedLine {
  itemId: string;
  amountMinor: number;
  assignments: readonly ItemizedAssignment[];
}

export interface ItemizedInput {
  /** Every line of the bill, each with the people who are on it. */
  items: readonly ItemizedLine[];
  /**
   * Service charge, taxes, tip and discounts — everything that is not a line
   * item. Negative values (a discount) are fine.
   */
  extrasMinor?: number;
  /**
   * `PROPORTIONAL` (default) charges extras in the ratio of each person's item
   * subtotal, which is how a percentage service charge actually works.
   * `EQUAL` divides them per head.
   */
  extrasMode?: 'PROPORTIONAL' | 'EQUAL';
  /** Needed when extras are split EQUAL, and used to absorb odd minor units. */
  participantIds?: readonly string[];
  payerUserId?: string;
  /** Reject a bill where some line has nobody on it. Defaults to true. */
  requireEveryItemAssigned?: boolean;
}

export interface ItemizedResult extends SplitResult {
  method: 'ITEMIZED';
  /** Per person: what their own items came to, before extras. */
  itemSubtotals: Record<string, number>;
  extrasMinor: number;
  perItem: Array<{ itemId: string; shares: Array<{ userId: string; amountMinor: number }> }>;
}

/**
 * Item-level splitting: each line is apportioned among the people on it, then
 * the extras are laid on top. Still exact to the minor unit.
 */
export function computeItemizedSplit(input: ItemizedInput): ItemizedResult {
  const {
    items,
    extrasMinor = 0,
    extrasMode = 'PROPORTIONAL',
    requireEveryItemAssigned = true
  } = input;

  // Check this before the roster, so a bill whose lines have nobody on them
  // reports the actual problem (which item) rather than "nobody is on any item".
  if (requireEveryItemAssigned) {
    const orphan = items.find((item) => item.assignments.length === 0);
    if (orphan) {
      throw new SplitError('ITEM_UNASSIGNED', `nobody is on item ${orphan.itemId}`, {
        itemId: orphan.itemId
      });
    }
  }

  const roster: string[] = [];
  const push = (userId: string) => {
    if (!roster.includes(userId)) roster.push(userId);
  };
  for (const id of input.participantIds ?? []) push(id);
  for (const item of items) for (const a of item.assignments) push(a.userId);

  if (roster.length === 0) {
    throw new SplitError('NO_PARTICIPANTS', 'nobody is on any item');
  }

  const subtotals: Record<string, number> = Object.fromEntries(roster.map((id) => [id, 0]));
  const perItem: ItemizedResult['perItem'] = [];

  for (const item of items) {
    if (item.assignments.length === 0) {
      if (requireEveryItemAssigned) {
        throw new SplitError('ITEM_UNASSIGNED', `nobody is on item ${item.itemId}`, {
          itemId: item.itemId
        });
      }
      perItem.push({ itemId: item.itemId, shares: [] });
      continue;
    }
    const weights = item.assignments.map((a) => a.weight ?? 1);
    if (weights.some((w) => w < 0)) {
      throw new SplitError('NEGATIVE_VALUE', `item ${item.itemId} has a negative weight`, {
        itemId: item.itemId
      });
    }
    const prefer = Math.max(
      0,
      item.assignments.findIndex((a) => a.userId === input.payerUserId)
    );
    const amounts = apportion(item.amountMinor, weights, prefer);
    perItem.push({
      itemId: item.itemId,
      shares: item.assignments.map((a, i) => ({ userId: a.userId, amountMinor: amounts[i]! }))
    });
    item.assignments.forEach((a, i) => {
      subtotals[a.userId] = (subtotals[a.userId] ?? 0) + amounts[i]!;
    });
  }

  const preferIndex = Math.max(0, roster.indexOf(input.payerUserId ?? ''));
  const extrasWeights =
    extrasMode === 'EQUAL' ? roster.map(() => 1) : roster.map((id) => subtotals[id] ?? 0);
  const extras = apportion(extrasMinor, extrasWeights, preferIndex);

  const itemsTotal = sumMinor(items.map((i) => i.amountMinor));
  const unassigned = items
    .filter((i) => i.assignments.length === 0)
    .reduce((a, i) => a + i.amountMinor, 0);
  const totalMinor = itemsTotal - unassigned + extrasMinor;

  return {
    method: 'ITEMIZED',
    totalMinor,
    extrasMinor,
    itemSubtotals: subtotals,
    perItem,
    shares: roster.map((userId, i) => {
      const amountMinor = (subtotals[userId] ?? 0) + extras[i]!;
      return { userId, amountMinor, weightBp: bpOf(amountMinor, totalMinor) };
    })
  };
}

/**
 * Turns a set of pairwise debts into the fewest transfers that clear them —
 * what the "settle up" screen shows. Greedy largest-creditor/largest-debtor
 * matching; for the handful of people in a real group this is optimal or within
 * one transfer of it.
 *
 * `net` is per person: positive = they are owed, negative = they owe.
 */
export function minimalTransfers(
  net: Readonly<Record<string, number>>
): Array<{ fromUserId: string; toUserId: string; amountMinor: number }> {
  const debtors = Object.entries(net)
    .filter(([, v]) => v < 0)
    .map(([userId, v]) => ({ userId, amount: -v }))
    .sort((a, b) => b.amount - a.amount || a.userId.localeCompare(b.userId));
  const creditors = Object.entries(net)
    .filter(([, v]) => v > 0)
    .map(([userId, v]) => ({ userId, amount: v }))
    .sort((a, b) => b.amount - a.amount || a.userId.localeCompare(b.userId));

  const transfers: Array<{ fromUserId: string; toUserId: string; amountMinor: number }> = [];
  let d = 0;
  let c = 0;
  while (d < debtors.length && c < creditors.length) {
    const debtor = debtors[d]!;
    const creditor = creditors[c]!;
    const amount = Math.min(debtor.amount, creditor.amount);
    if (amount > 0) {
      transfers.push({ fromUserId: debtor.userId, toUserId: creditor.userId, amountMinor: amount });
    }
    debtor.amount -= amount;
    creditor.amount -= amount;
    if (debtor.amount === 0) d += 1;
    if (creditor.amount === 0) c += 1;
  }
  return transfers;
}

function requireValues(participants: readonly SplitParticipant[], method: string): number[] {
  return participants.map((p) => {
    if (p.value === undefined) {
      throw new SplitError('MISSING_VALUE', `${method} split needs a value for ${p.userId}`, {
        userId: p.userId
      });
    }
    if (p.value < 0) {
      throw new SplitError('NEGATIVE_VALUE', `${p.userId} has a negative value`, {
        userId: p.userId
      });
    }
    if (!Number.isInteger(p.value)) {
      throw new SplitError('NEGATIVE_VALUE', `${p.userId}'s value must be an integer`, {
        userId: p.userId
      });
    }
    return p.value;
  });
}

function bpOf(part: number, total: number): number {
  if (total === 0) return 0;
  return Math.round((part / total) * BP_TOTAL);
}
