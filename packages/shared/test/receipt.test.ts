import { describe, expect, it } from 'vitest';
import { parseReceiptText } from '../src/receipt.js';

const restaurant = `SOCIAL OFFLINE
Linking Road, Bandra West
GSTIN: 27AABCU9603R1ZX
Invoice No: SO/2026/4471
Date: 19-09-2026

Aerated Beverages    2    120.00    240.00
Chilli Cheese Toast  1    345.00    345.00
LIIT Pitcher         2    650.00    1,300.00
Butter Chicken       1    400.00    400.00

Sub Total                          2,285.00
Service Charge 10%                   228.50
CGST 2.5%                             30.84
SGST 2.5%                             30.84
Round Off                             -0.18
Net Amount                         2,575.00
Thank you, visit again`;

describe('parseReceiptText', () => {
  it('reads the items with quantities and rates', () => {
    const parsed = parseReceiptText(restaurant);
    expect(parsed.items).toHaveLength(4);
    expect(parsed.items[0]).toMatchObject({
      name: 'Aerated Beverages',
      quantityMilli: 2000,
      unitPriceMinor: 12_000,
      amountMinor: 24_000
    });
    expect(parsed.items[2]!.amountMinor).toBe(130_000);
  });

  it('reads the totals and the tax breakdown', () => {
    const parsed = parseReceiptText(restaurant);
    expect(parsed.totals.itemTotalMinor).toBe(228_500);
    expect(parsed.totals.serviceChargeMinor).toBe(22_850);
    expect(parsed.totals.taxMinor).toBe(3084 + 3084);
    expect(parsed.totals.roundOffMinor).toBe(-18);
    expect(parsed.totals.totalMinor).toBe(257_500);
  });

  it('keeps each tax line with its rate', () => {
    const parsed = parseReceiptText(restaurant);
    const kinds = parsed.taxLines.map((t) => t.kind);
    expect(kinds).toContain('SERVICE_CHARGE');
    expect(kinds).toContain('CGST');
    expect(kinds).toContain('SGST');
    expect(parsed.taxLines.find((t) => t.kind === 'SERVICE_CHARGE')!.rateBp).toBe(1000);
    expect(parsed.taxLines.find((t) => t.kind === 'CGST')!.amountMinor).toBe(3084);
  });

  it('reads the merchant, the date and the invoice number', () => {
    const parsed = parseReceiptText(restaurant);
    expect(parsed.merchantName).toBe('SOCIAL OFFLINE');
    expect(parsed.occurredAt).toBe('2026-09-19');
    expect(parsed.invoiceNumber).toBe('SO/2026/4471');
  });

  it('is confident when the arithmetic checks out', () => {
    const parsed = parseReceiptText(restaurant);
    expect(parsed.confidence).toBeGreaterThan(0.8);
    expect(parsed.warnings).toEqual([]);
  });

  it('ignores GSTIN, invoice and footer lines instead of treating them as items', () => {
    const names = parseReceiptText(restaurant).items.map((i) => i.name.toLowerCase());
    expect(names.some((n) => n.includes('gstin'))).toBe(false);
    expect(names.some((n) => n.includes('invoice'))).toBe(false);
    expect(names.some((n) => n.includes('thank you'))).toBe(false);
  });

  it('handles a quantity-first supermarket layout', () => {
    const parsed = parseReceiptText(`DMART
2 x Amul Milk 1L 64.00
1 x Aashirvaad Atta 5kg 285.00
Total 349.00`);
    expect(parsed.items).toHaveLength(2);
    expect(parsed.items[0]!.quantityMilli).toBe(2000);
    expect(parsed.items[0]!.unitPriceMinor).toBe(3200);
    expect(parsed.totals.totalMinor).toBe(34_900);
  });

  it('adds the lines up when no total is printed, and says so', () => {
    const parsed = parseReceiptText(`CORNER STORE
Chips 40.00
Cola 60.00`);
    expect(parsed.totals.totalMinor).toBe(10_000);
    expect(parsed.warnings.join(' ')).toMatch(/added the lines up/);
  });

  it('warns when the printed total disagrees with the parts', () => {
    const parsed = parseReceiptText(`SHOP
Thing 100.00
Sub Total 100.00
Net Amount 900.00`);
    expect(parsed.warnings.join(' ')).toMatch(/do not match|add up to/);
    expect(parsed.confidence).toBeLessThan(0.8);
  });

  it('reads a discount as a positive discount, not a negative item', () => {
    const parsed = parseReceiptText(`SHOP
Shirt 1000.00
Sub Total 1000.00
Discount 200.00
Total 800.00`);
    expect(parsed.totals.discountMinor).toBe(20_000);
    expect(parsed.totals.totalMinor).toBe(80_000);
    expect(parsed.warnings).toEqual([]);
  });

  it('survives an empty read without throwing', () => {
    const parsed = parseReceiptText('');
    expect(parsed.items).toEqual([]);
    expect(parsed.confidence).toBeLessThan(0.3);
    expect(parsed.warnings.length).toBeGreaterThan(0);
  });
});
