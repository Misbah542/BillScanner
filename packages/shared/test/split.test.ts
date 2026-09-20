import { describe, expect, it } from 'vitest';
import { sumMinor } from '../src/money.js';
import {
  BP_TOTAL,
  SplitError,
  computeItemizedSplit,
  computeSplit,
  minimalTransfers
} from '../src/split.js';

const four = ['you', 'aditi', 'rohan', 'sneha'];

describe('EQUAL', () => {
  it('splits a divisible bill evenly', () => {
    const result = computeSplit({
      method: 'EQUAL',
      totalMinor: 257_500,
      participants: four.map((userId) => ({ userId }))
    });
    expect(result.shares.map((s) => s.amountMinor)).toEqual([64_375, 64_375, 64_375, 64_375]);
  });

  it('gives the payer the odd paise', () => {
    const result = computeSplit({
      method: 'EQUAL',
      totalMinor: 257_501,
      participants: four.map((userId) => ({ userId })),
      payerUserId: 'rohan'
    });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(257_501);
    expect(result.shares.find((s) => s.userId === 'rohan')!.amountMinor).toBe(64_376);
  });

  it('rejects an empty participant list', () => {
    expect(() => computeSplit({ method: 'EQUAL', totalMinor: 100, participants: [] })).toThrow(
      SplitError
    );
  });

  it('rejects the same person twice', () => {
    expect(() =>
      computeSplit({
        method: 'EQUAL',
        totalMinor: 100,
        participants: [{ userId: 'you' }, { userId: 'you' }]
      })
    ).toThrowError(/appears twice/);
  });
});

describe('EXACT (unequal)', () => {
  it('accepts amounts that add up', () => {
    const result = computeSplit({
      method: 'EXACT',
      totalMinor: 257_500,
      participants: [
        { userId: 'you', value: 90_000 },
        { userId: 'aditi', value: 70_000 },
        { userId: 'rohan', value: 57_500 },
        { userId: 'sneha', value: 40_000 }
      ]
    });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(257_500);
  });

  it('refuses amounts that do not, and says by how much', () => {
    try {
      computeSplit({
        method: 'EXACT',
        totalMinor: 257_500,
        participants: [
          { userId: 'you', value: 90_000 },
          { userId: 'aditi', value: 70_000 }
        ]
      });
      throw new Error('should have thrown');
    } catch (error) {
      expect(error).toBeInstanceOf(SplitError);
      const split = error as SplitError;
      expect(split.code).toBe('EXACT_SUM_MISMATCH');
      expect(split.detail?.differenceMinor).toBe(97_500);
    }
  });
});

describe('PERCENT', () => {
  it('splits on basis points and stays exact', () => {
    const result = computeSplit({
      method: 'PERCENT',
      totalMinor: 257_500,
      participants: [
        { userId: 'you', value: 3500 },
        { userId: 'aditi', value: 2500 },
        { userId: 'rohan', value: 2200 },
        { userId: 'sneha', value: 1800 }
      ]
    });
    expect(result.shares.map((s) => s.amountMinor)).toEqual([90_125, 64_375, 56_650, 46_350]);
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(257_500);
  });

  it('stays exact when the percentages produce a remainder', () => {
    const result = computeSplit({
      method: 'PERCENT',
      totalMinor: 100_00,
      participants: [
        { userId: 'a', value: 3333 },
        { userId: 'b', value: 3333 },
        { userId: 'c', value: 3334 }
      ]
    });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(100_00);
  });

  it('refuses percentages that do not reach 100%', () => {
    expect(() =>
      computeSplit({
        method: 'PERCENT',
        totalMinor: 1000,
        participants: [
          { userId: 'a', value: 5000 },
          { userId: 'b', value: 4000 }
        ]
      })
    ).toThrowError(/90% instead of 100%/);
  });

  it('treats BP_TOTAL as the whole', () => {
    const result = computeSplit({
      method: 'PERCENT',
      totalMinor: 999,
      participants: [{ userId: 'solo', value: BP_TOTAL }]
    });
    expect(result.shares[0]!.amountMinor).toBe(999);
  });
});

describe('SHARES', () => {
  it('weights by share count', () => {
    const result = computeSplit({
      method: 'SHARES',
      totalMinor: 257_500,
      participants: [
        { userId: 'you', value: 2 },
        { userId: 'aditi', value: 1 },
        { userId: 'rohan', value: 1 },
        { userId: 'sneha', value: 1 }
      ]
    });
    expect(result.shares.map((s) => s.amountMinor)).toEqual([103_000, 51_500, 51_500, 51_500]);
  });

  it('refuses an all-zero share set', () => {
    expect(() =>
      computeSplit({
        method: 'SHARES',
        totalMinor: 100,
        participants: [{ userId: 'a', value: 0 }]
      })
    ).toThrowError(/needs a share/);
  });
});

describe('ITEMIZED', () => {
  const items = [
    { itemId: 'drinks', amountMinor: 24_000, assignments: [{ userId: 'you' }, { userId: 'aditi' }] },
    { itemId: 'toast', amountMinor: 34_500, assignments: [{ userId: 'rohan' }] },
    {
      itemId: 'pitcher',
      amountMinor: 130_000,
      assignments: four.map((userId) => ({ userId }))
    },
    { itemId: 'chicken', amountMinor: 40_000, assignments: [{ userId: 'sneha' }] }
  ];

  it('charges each person for their own items plus a proportional share of extras', () => {
    const result = computeItemizedSplit({ items, extrasMinor: 29_018, payerUserId: 'you' });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(result.totalMinor);
    expect(result.totalMinor).toBe(24_000 + 34_500 + 130_000 + 40_000 + 29_018);
    // Rohan is on the toast plus a quarter of the pitcher.
    expect(result.itemSubtotals.rohan).toBe(34_500 + 32_500);
  });

  it('can split extras per head instead', () => {
    const result = computeItemizedSplit({
      items,
      extrasMinor: 40_000,
      extrasMode: 'EQUAL',
      payerUserId: 'you'
    });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(result.totalMinor);
    const extrasOnly = result.shares.map((s) => s.amountMinor - result.itemSubtotals[s.userId]!);
    expect(extrasOnly).toEqual([10_000, 10_000, 10_000, 10_000]);
  });

  it('handles a discount as negative extras', () => {
    const result = computeItemizedSplit({ items, extrasMinor: -10_000, payerUserId: 'you' });
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(result.totalMinor);
    expect(result.totalMinor).toBe(228_500 - 10_000);
  });

  it('weights an item when one person had two of it', () => {
    const result = computeItemizedSplit({
      items: [
        {
          itemId: 'drinks',
          amountMinor: 30_000,
          assignments: [
            { userId: 'you', weight: 2 },
            { userId: 'aditi', weight: 1 }
          ]
        }
      ],
      payerUserId: 'you'
    });
    expect(result.shares.map((s) => s.amountMinor)).toEqual([20_000, 10_000]);
  });

  it('complains about an item with nobody on it', () => {
    expect(() =>
      computeItemizedSplit({ items: [{ itemId: 'orphan', amountMinor: 100, assignments: [] }] })
    ).toThrowError(/nobody is on item orphan/);
  });

  it('can be told to allow unassigned items and leaves them out of the total', () => {
    const result = computeItemizedSplit({
      items: [
        { itemId: 'mine', amountMinor: 100, assignments: [{ userId: 'you' }] },
        { itemId: 'orphan', amountMinor: 500, assignments: [] }
      ],
      requireEveryItemAssigned: false
    });
    expect(result.totalMinor).toBe(100);
    expect(sumMinor(result.shares.map((s) => s.amountMinor))).toBe(100);
  });
});

describe('minimalTransfers', () => {
  it('collapses a web of debts into few transfers', () => {
    const transfers = minimalTransfers({ you: 188_400, aditi: -188_400 });
    expect(transfers).toEqual([{ fromUserId: 'aditi', toUserId: 'you', amountMinor: 188_400 }]);
  });

  it('clears every balance exactly', () => {
    const net = { you: 186_000, aditi: -124_000, rohan: -62_000, sneha: 0 };
    const transfers = minimalTransfers(net);
    const applied: Record<string, number> = { ...net };
    for (const t of transfers) {
      applied[t.fromUserId] = applied[t.fromUserId]! + t.amountMinor;
      applied[t.toUserId] = applied[t.toUserId]! - t.amountMinor;
    }
    expect(Object.values(applied).every((v) => v === 0)).toBe(true);
    expect(transfers.length).toBeLessThanOrEqual(2);
  });

  it('returns nothing when everyone is square', () => {
    expect(minimalTransfers({ you: 0, aditi: 0 })).toEqual([]);
  });

  it('needs at most n-1 transfers for n people', () => {
    const net = { a: 300, b: 200, c: -100, d: -150, e: -250 };
    expect(minimalTransfers(net).length).toBeLessThanOrEqual(4);
  });
});
