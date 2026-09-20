import { describe, expect, it } from 'vitest';
import { apportion, formatMinor, fromMinor, sumMinor, toMinor } from '../src/money.js';

describe('toMinor', () => {
  it('converts decimal strings', () => {
    expect(toMinor('2575.00')).toBe(257_500);
    expect(toMinor('0.01')).toBe(1);
    expect(toMinor('643.75')).toBe(64_375);
  });

  it('accepts grouped digits in both Indian and Western styles', () => {
    expect(toMinor('1,300.00')).toBe(130_000);
    expect(toMinor('12,499.00')).toBe(1_249_900);
    expect(toMinor('2,57,500.00')).toBe(25_750_000);
    expect(toMinor('1.300,00')).toBe(130_000);
    expect(toMinor('12,50')).toBe(1250);
  });

  it('handles a missing fraction and a negative sign', () => {
    expect(toMinor('400')).toBe(40_000);
    expect(toMinor('-0.18')).toBe(-18);
  });

  it('rounds half-up when given more precision than the currency has', () => {
    expect(toMinor('10.005')).toBe(1001);
    expect(toMinor('10.004')).toBe(1000);
  });

  it('respects currencies with other exponents', () => {
    expect(toMinor('1000', 'JPY')).toBe(1000);
    expect(toMinor('1.234', 'KWD')).toBe(1234);
  });

  it('rejects junk', () => {
    expect(() => toMinor('abc')).toThrow(RangeError);
    expect(() => toMinor('')).toThrow(RangeError);
  });
});

describe('fromMinor and formatMinor', () => {
  it('round-trips', () => {
    for (const value of ['0.00', '1.05', '2575.00', '99999.99']) {
      expect(fromMinor(toMinor(value))).toBe(value);
    }
  });

  it('formats negatives and zero-exponent currencies', () => {
    expect(fromMinor(-18)).toBe('-0.18');
    expect(fromMinor(1000, 'JPY')).toBe('1000');
  });

  it('renders a currency string', () => {
    expect(formatMinor(257_500, 'INR', 'en-IN')).toContain('2,575.00');
  });

  it('refuses fractional minor units', () => {
    expect(() => fromMinor(1.5)).toThrow(RangeError);
    expect(() => sumMinor([1, 2.5])).toThrow(RangeError);
  });
});

describe('apportion', () => {
  it('never loses or invents a paisa', () => {
    for (const total of [1, 7, 100, 257_500, 999_999]) {
      for (const n of [1, 2, 3, 4, 7, 11]) {
        const shares = apportion(total, Array.from({ length: n }, () => 1));
        expect(sumMinor(shares)).toBe(total);
      }
    }
  });

  it('spreads the remainder one unit at a time, not all onto one person', () => {
    // 100 paise across 3 == 34 + 33 + 33, never 34 + 34 + 32.
    expect(apportion(100, [1, 1, 1])).toEqual([34, 33, 33]);
    expect(apportion(10, [1, 1, 1, 1, 1, 1, 1])).toEqual([2, 2, 2, 1, 1, 1, 1]);
  });

  it('gives the odd unit to the preferred index on a tie', () => {
    expect(apportion(100, [1, 1, 1], 2)).toEqual([33, 33, 34]);
  });

  it('respects weights', () => {
    expect(apportion(1000, [3, 1])).toEqual([750, 250]);
    expect(apportion(257_500, [35, 25, 22, 18])).toEqual([90_125, 64_375, 56_650, 46_350]);
  });

  it('handles negative totals (a refund) exactly', () => {
    const shares = apportion(-100, [1, 1, 1]);
    expect(sumMinor(shares)).toBe(-100);
    expect(shares).toEqual([-34, -33, -33]);
  });

  it('skips zero-weight participants', () => {
    expect(apportion(100, [1, 0, 1])).toEqual([50, 0, 50]);
  });

  it('falls back to an even split when every weight is zero', () => {
    expect(sumMinor(apportion(100, [0, 0, 0]))).toBe(100);
  });

  it('is exact for a total that does not divide, repeatedly', () => {
    // The classic: ₹0.10 three ways, a hundred times over, must stay exact.
    let running = 0;
    for (let i = 0; i < 100; i += 1) running += sumMinor(apportion(10, [1, 1, 1]));
    expect(running).toBe(1000);
  });
});
