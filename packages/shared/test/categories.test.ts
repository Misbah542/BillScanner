import { describe, expect, it } from 'vitest';
import { CATEGORIES, classify, normalizeMerchant } from '../src/categories.js';

describe('normalizeMerchant', () => {
  it('strips payment-rail decoration', () => {
    expect(normalizeMerchant('UPI/SWIGGY*ORDER/HDFC')).toBe('swiggy');
    expect(normalizeMerchant('POS/SOCIAL OFFLINE BANDRA')).toBe('social offline bandra');
    expect(normalizeMerchant('AMAZON.IN-PMTS')).toBe('amazon');
  });

  it('drops company suffixes and reference digits', () => {
    expect(normalizeMerchant('CROMA RETAIL PVT LTD 889210')).toBe('croma');
  });

  it('makes the same shop match itself across channels', () => {
    expect(normalizeMerchant('ZUDIO - PHOENIX MALL')).toBe(normalizeMerchant('zudio   phoenix mall'));
  });
});

describe('classify', () => {
  it('puts a restaurant bill in Restaurants and explains why', () => {
    const [top] = classify({
      merchant: 'Social Offline, Bandra West',
      itemNames: ['LIIT Pitcher', 'Chilli Cheese Toast', 'Service Charge']
    });
    expect(top!.slug).toBe('restaurant');
    expect(top!.confidence).toBeGreaterThan(0.6);
    expect(top!.reasons.join(' ')).toMatch(/social offline|service charge|liit/i);
  });

  it('recognises groceries from a delivery merchant', () => {
    expect(classify({ merchant: 'BigBasket' })[0]!.slug).toBe('groceries');
  });

  it('recognises electronics retail as shopping', () => {
    expect(classify({ merchant: 'CROMA, PHOENIX' })[0]!.slug).toBe('shopping');
  });

  it('recognises a cab as travel', () => {
    expect(classify({ merchant: 'UBER INDIA' })[0]!.slug).toBe('travel');
  });

  it('treats a credit as income rather than spending', () => {
    const [top] = classify({ merchant: 'ACME PAYROLL', alertText: 'salary credited', direction: 'CREDIT' });
    expect(top!.slug).toBe('income');
  });

  it('lets the user history outrank the keyword tables', () => {
    const withoutHistory = classify({ merchant: 'Blue Tokai' })[0]!.slug;
    const withHistory = classify({ merchant: 'Blue Tokai', history: { groceries: 7 } })[0]!.slug;
    expect(withHistory).toBe('groceries');
    expect(withHistory).not.toBe(withoutHistory === 'groceries' ? 'never' : withoutHistory);
  });

  it('falls back to Uncategorised rather than guessing', () => {
    const [top] = classify({ merchant: 'Qzzx Vvt' });
    expect(top!.slug).toBe('other');
    expect(top!.confidence).toBe(0);
  });

  it('returns suggestions best-first and no more than asked for', () => {
    const suggestions = classify(
      { merchant: 'Swiggy Instamart', itemNames: ['milk', 'bread', 'onion'] },
      3
    );
    expect(suggestions.length).toBeLessThanOrEqual(3);
    for (let i = 1; i < suggestions.length; i += 1) {
      expect(suggestions[i - 1]!.confidence).toBeGreaterThanOrEqual(suggestions[i]!.confidence);
    }
  });

  it('keeps Uncategorised last in the taxonomy so seeding order is stable', () => {
    expect(CATEGORIES[CATEGORIES.length - 1]!.slug).toBe('other');
  });
});
