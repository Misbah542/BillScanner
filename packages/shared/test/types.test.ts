import { describe, expect, it } from 'vitest';
import { CreateExpenseInput, IngestAlertInput, MonthlySummaryQuery } from '../src/types.js';

describe('CreateExpenseInput', () => {
  it('accepts a bare personal expense straight off a bank alert', () => {
    const parsed = CreateExpenseInput.parse({
      kind: 'PERSONAL',
      source: 'ALERT',
      merchantName: 'SWIGGY',
      totalMinor: 48_600
    });
    expect(parsed.items).toEqual([]);
    expect(parsed.currency).toBe('INR');
    expect(parsed.kind).toBe('PERSONAL');
  });

  it('refuses participants on a personal expense', () => {
    const result = CreateExpenseInput.safeParse({
      kind: 'PERSONAL',
      totalMinor: 1000,
      participants: [{ userId: '11111111-1111-4111-8111-111111111111' }]
    });
    expect(result.success).toBe(false);
  });

  it('requires a split method on a shared expense', () => {
    const result = CreateExpenseInput.safeParse({ kind: 'SHARED', totalMinor: 1000 });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.some((i) => i.path.includes('splitMethod'))).toBe(true);
    }
  });

  it('accepts a shared expense with participants identified only by email or phone', () => {
    const parsed = CreateExpenseInput.parse({
      kind: 'SHARED',
      totalMinor: 257_500,
      splitMethod: 'EQUAL',
      participants: [
        { email: 'Aditi@Gmail.com' },
        { phone: '+919820011234', displayName: 'Rohan' }
      ]
    });
    expect(parsed.participants![0]!.email).toBe('aditi@gmail.com');
  });

  it('rejects a fractional amount', () => {
    expect(CreateExpenseInput.safeParse({ totalMinor: 486.5 }).success).toBe(false);
  });

  it('catches items that do not add up to the stated item total', () => {
    const result = CreateExpenseInput.safeParse({
      totalMinor: 100_000,
      itemTotalMinor: 100_000,
      items: [{ name: 'Thing', unitPriceMinor: 5000, amountMinor: 5000 }]
    });
    expect(result.success).toBe(false);
  });
});

describe('IngestAlertInput', () => {
  it('accepts a parsed alert without the message body', () => {
    const parsed = IngestAlertInput.parse({
      direction: 'DEBIT',
      amountMinor: 48_600,
      merchantRaw: 'SWIGGY',
      accountMask: '4471',
      occurredAt: '2026-09-20T13:12:00+05:30',
      fingerprint: 'a1b2c3d4'
    });
    expect(parsed.channel).toBe('SMS');
    expect(parsed.rawBody).toBeUndefined();
    expect(parsed.accountKind).toBe('UNKNOWN');
  });

  it('rejects a full account number masquerading as a mask', () => {
    const result = IngestAlertInput.safeParse({
      direction: 'DEBIT',
      amountMinor: 100,
      accountMask: '1234567890123456',
      occurredAt: new Date(),
      fingerprint: 'abcd'
    });
    expect(result.success).toBe(false);
  });
});

describe('MonthlySummaryQuery', () => {
  it('defaults to all expenses in the India timezone', () => {
    const parsed = MonthlySummaryQuery.parse({});
    expect(parsed.kind).toBe('ALL');
    expect(parsed.timezone).toBe('Asia/Kolkata');
  });

  it('accepts a month and rejects a malformed one', () => {
    expect(MonthlySummaryQuery.parse({ month: '2026-09' }).month).toBe('2026-09');
    expect(MonthlySummaryQuery.safeParse({ month: '2026-13' }).success).toBe(false);
    expect(MonthlySummaryQuery.safeParse({ month: 'September' }).success).toBe(false);
  });
});
