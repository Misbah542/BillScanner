import { toMinor } from './money.js';

/**
 * Turns the plain text an OCR engine returns into structured items and totals.
 *
 * This runs on the SERVER, never on the phone: recognition and parsing both stay
 * behind the API so the provider can be swapped, the rules can be fixed for
 * everyone at once without an app release, and a weak phone is not doing the
 * work. The client uploads an image and receives this shape back.
 */

export interface ParsedReceiptItem {
  name: string;
  /** Thousandths of a unit, so 0.5 kg is 500 — receipts do sell half-kilos. */
  quantityMilli: number;
  unitPriceMinor: number;
  amountMinor: number;
  /** The source line, kept so the app can show what it read and let the user fix it. */
  sourceLine: string;
}

export interface ParsedReceiptTotals {
  itemTotalMinor: number;
  serviceChargeMinor: number;
  taxMinor: number;
  discountMinor: number;
  tipMinor: number;
  roundOffMinor: number;
  totalMinor: number;
}

export interface ParsedReceiptTaxLine {
  label: string;
  kind: 'SERVICE_CHARGE' | 'CGST' | 'SGST' | 'IGST' | 'VAT' | 'CESS' | 'OTHER';
  rateBp?: number;
  amountMinor: number;
}

export interface ParsedReceipt {
  merchantName?: string;
  occurredAt?: string;
  invoiceNumber?: string;
  currency: string;
  items: ParsedReceiptItem[];
  taxLines: ParsedReceiptTaxLine[];
  totals: ParsedReceiptTotals;
  /** 0..1 — how internally consistent the read was. Drives the "check this" nudge. */
  confidence: number;
  /** Human-readable notes about what did not add up, surfaced in the review screen. */
  warnings: string[];
}

// A grouped number must actually carry a group (`,\d{2,3}` at least once), so
// that `1000.00` falls through to the plain branch and is matched whole instead
// of the first three digits.
const NUM = String.raw`\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?`;

const TOTAL_LABELS: Array<{
  keys: RegExp;
  field: keyof ParsedReceiptTotals | 'ignore';
  taxKind?: ParsedReceiptTaxLine['kind'];
}> = [
  { keys: /\b(?:sub[ -]?total|item(?:s)? total|gross(?: amount)?|total qty amount)\b/i, field: 'itemTotalMinor' },
  { keys: /\bservice charge\b/i, field: 'serviceChargeMinor', taxKind: 'SERVICE_CHARGE' },
  { keys: /\b(?:c\.?gst|central gst)\b/i, field: 'taxMinor', taxKind: 'CGST' },
  { keys: /\b(?:s\.?gst|state gst)\b/i, field: 'taxMinor', taxKind: 'SGST' },
  { keys: /\b(?:i\.?gst|integrated gst)\b/i, field: 'taxMinor', taxKind: 'IGST' },
  { keys: /\bvat\b/i, field: 'taxMinor', taxKind: 'VAT' },
  { keys: /\bcess\b/i, field: 'taxMinor', taxKind: 'CESS' },
  { keys: /\b(?:gst|tax|service tax)\b/i, field: 'taxMinor', taxKind: 'OTHER' },
  { keys: /\b(?:discount|savings|coupon|promo)\b/i, field: 'discountMinor' },
  { keys: /\b(?:tip|gratuity)\b/i, field: 'tipMinor' },
  { keys: /\bround(?:ed)?[ -]?off\b/i, field: 'roundOffMinor' },
  { keys: /\b(?:net (?:amount|payable|total)|grand total|amount payable|total payable|bill (?:amount|total)|total amount)\b/i, field: 'totalMinor' },
  // A bare leading "Total" — anchored, so "Sub Total" has already been claimed above.
  { keys: /^total\b/i, field: 'totalMinor' },
  { keys: /\b(?:cash|card|upi|change|tendered|balance|paid|rounded)\b/i, field: 'ignore' }
];

const NOISE = /\b(?:gstin|fssai|cin|pan|tin|invoice|bill no|table|covers|steward|cashier|thank you|visit again|customer copy|terms|www\.|https?:|phone|tel|mob|order (?:no|id))\b/i;

export function parseReceiptText(rawText: string, currency = 'INR'): ParsedReceipt {
  const lines = rawText
    .split(/\r?\n/)
    .map((l) => l.replace(/\s+/g, ' ').trim())
    .filter((l) => l.length > 0);

  const warnings: string[] = [];
  const items: ParsedReceiptItem[] = [];
  const taxLines: ParsedReceiptTaxLine[] = [];
  const totals: ParsedReceiptTotals = {
    itemTotalMinor: 0,
    serviceChargeMinor: 0,
    taxMinor: 0,
    discountMinor: 0,
    tipMinor: 0,
    roundOffMinor: 0,
    totalMinor: 0
  };
  const seenTotals = new Set<string>();

  const merchantName = guessMerchant(lines);
  const occurredAt = guessDate(rawText);
  const invoiceNumber = guessInvoice(rawText);

  for (const line of lines) {
    const label = TOTAL_LABELS.find((entry) => entry.keys.test(line));
    if (label) {
      if (label.field === 'ignore') continue;
      const amount = lastAmount(line, currency);
      if (amount === undefined) continue;

      const signed = /\b(?:discount|savings|coupon|promo)\b/i.test(line) ? Math.abs(amount) : amount;
      if (label.field === 'taxMinor' || label.field === 'serviceChargeMinor') {
        taxLines.push({
          label: line.replace(new RegExp(`[₹]?\\s*${NUM}\\s*$`), '').replace(/[:\-\s]+$/, '').trim() || label.taxKind || 'Tax',
          kind: label.taxKind ?? 'OTHER',
          ...(rateOf(line) === undefined ? {} : { rateBp: rateOf(line) }),
          amountMinor: signed
        });
      }
      if (label.field === 'taxMinor') {
        totals.taxMinor += signed;
      } else {
        // A receipt can print "Total" twice; the first labelled value wins.
        if (seenTotals.has(label.field)) continue;
        totals[label.field] = signed;
      }
      seenTotals.add(label.field);
      continue;
    }

    if (NOISE.test(line)) continue;

    const item = parseItemLine(line, currency);
    if (item) items.push(item);
  }

  const itemsSum = items.reduce((a, i) => a + i.amountMinor, 0);
  if (totals.itemTotalMinor === 0 && itemsSum > 0) totals.itemTotalMinor = itemsSum;

  const computed =
    totals.itemTotalMinor +
    totals.serviceChargeMinor +
    totals.taxMinor +
    totals.tipMinor +
    totals.roundOffMinor -
    totals.discountMinor;

  if (totals.totalMinor === 0) {
    totals.totalMinor = computed;
    if (computed > 0) warnings.push('No total was printed on the receipt; SnapTab added the lines up.');
  } else if (Math.abs(totals.totalMinor - computed) > 100) {
    warnings.push(
      `The parts add up to ${(computed / 100).toFixed(2)} but the receipt says ${(totals.totalMinor / 100).toFixed(2)}.`
    );
  }

  if (items.length > 0 && Math.abs(itemsSum - totals.itemTotalMinor) > 100) {
    warnings.push('The item lines do not match the printed subtotal — an item may have been missed.');
  }
  if (items.length === 0) warnings.push('No item lines were recognised.');
  if (!merchantName) warnings.push('The merchant name could not be read.');

  return {
    ...(merchantName ? { merchantName } : {}),
    ...(occurredAt ? { occurredAt } : {}),
    ...(invoiceNumber ? { invoiceNumber } : {}),
    currency,
    items,
    taxLines,
    totals,
    confidence: scoreConfidence({ items, totals, computed, warnings, hasMerchant: !!merchantName }),
    warnings
  };
}

function parseItemLine(line: string, currency: string): ParsedReceiptItem | null {
  // `Chilli Cheese Toast 1 345.00 345.00`  (name, qty, rate, amount)
  const quad = new RegExp(
    String.raw`^(?<name>.*?[A-Za-z].*?)\s+(?<qty>\d+(?:\.\d{1,3})?)\s*(?:x|nos?|pcs?|qty)?\s+[₹]?\s*(?<rate>${NUM})\s+[₹]?\s*(?<amount>${NUM})$`,
    'i'
  ).exec(line);
  if (quad?.groups) {
    const { name, qty, rate, amount } = quad.groups as Record<string, string>;
    const item = buildItem(name!, qty!, rate!, amount!, line, currency);
    if (item) return item;
  }

  // `2 x Aerated Beverages 240.00`  (qty first)
  const qtyFirst = new RegExp(
    String.raw`^(?<qty>\d+(?:\.\d{1,3})?)\s*(?:x|\*)\s*(?<name>.*?[A-Za-z].*?)\s+[₹]?\s*(?<amount>${NUM})$`,
    'i'
  ).exec(line);
  if (qtyFirst?.groups) {
    const { name, qty, amount } = qtyFirst.groups as Record<string, string>;
    const amountMinor = safe(amount!, currency);
    const quantityMilli = Math.round(Number(qty) * 1000);
    if (amountMinor !== undefined && quantityMilli > 0) {
      return {
        name: tidyName(name!),
        quantityMilli,
        unitPriceMinor: Math.round(amountMinor / (quantityMilli / 1000)),
        amountMinor,
        sourceLine: line
      };
    }
  }

  // `Butter Chicken 400.00`  (no quantity printed)
  const pair = new RegExp(String.raw`^(?<name>.*?[A-Za-z].*?)\s+[₹]?\s*(?<amount>${NUM})$`).exec(line);
  if (pair?.groups) {
    const { name, amount } = pair.groups as Record<string, string>;
    const amountMinor = safe(amount!, currency);
    const tidy = tidyName(name!);
    if (amountMinor === undefined || amountMinor <= 0) return null;
    if (tidy.length < 2 || !/[A-Za-z]{2}/.test(tidy)) return null;
    return {
      name: tidy,
      quantityMilli: 1000,
      unitPriceMinor: amountMinor,
      amountMinor,
      sourceLine: line
    };
  }

  return null;
}

function buildItem(
  name: string,
  qty: string,
  rate: string,
  amount: string,
  line: string,
  currency: string
): ParsedReceiptItem | null {
  const quantityMilli = Math.round(Number(qty) * 1000);
  const unitPriceMinor = safe(rate, currency);
  const amountMinor = safe(amount, currency);
  if (quantityMilli <= 0 || unitPriceMinor === undefined || amountMinor === undefined) return null;
  const tidy = tidyName(name);
  if (tidy.length < 2 || !/[A-Za-z]{2}/.test(tidy)) return null;
  return { name: tidy, quantityMilli, unitPriceMinor, amountMinor, sourceLine: line };
}

function tidyName(raw: string): string {
  return raw
    .replace(/^[\s\d.)\-*#]+/, '')
    .replace(/[\s.:\-]+$/, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

function lastAmount(line: string, currency: string): number | undefined {
  const matches = [...line.matchAll(new RegExp(String.raw`(-)?[₹]?\s*(${NUM})`, 'g'))];
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const match = matches[i]!;
    // Skip a percentage — "Service Charge 10% 228.50" has two numbers.
    const after = line.slice((match.index ?? 0) + match[0].length, (match.index ?? 0) + match[0].length + 1);
    if (after === '%') continue;
    const value = safe(match[2]!, currency);
    if (value === undefined) continue;
    return match[1] ? -value : value;
  }
  return undefined;
}

function rateOf(line: string): number | undefined {
  const match = /(\d{1,2}(?:\.\d{1,2})?)\s*%/.exec(line);
  if (!match) return undefined;
  return Math.round(Number(match[1]) * 100);
}

function guessMerchant(lines: readonly string[]): string | undefined {
  for (const line of lines.slice(0, 6)) {
    if (NOISE.test(line)) continue;
    if (/\d{3}/.test(line)) continue;
    const letters = line.replace(/[^A-Za-z]/g, '').length;
    if (letters >= 4 && line.length <= 48) return line.replace(/\s{2,}/g, ' ').trim();
  }
  return undefined;
}

function guessDate(text: string): string | undefined {
  const match =
    /\b(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})\b/.exec(text) ??
    /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(text);
  if (!match) return undefined;
  let year: number;
  let month: number;
  let day: number;
  if (match[1]!.length === 4) {
    year = Number(match[1]);
    month = Number(match[2]);
    day = Number(match[3]);
  } else {
    day = Number(match[1]);
    month = Number(match[2]);
    year = Number(match[3]!.length === 2 ? `20${match[3]}` : match[3]);
  }
  if (month < 1 || month > 12 || day < 1 || day > 31) return undefined;
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function guessInvoice(text: string): string | undefined {
  const match = /\b(?:invoice|bill|inv)\s*(?:no\.?|number|#)?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9/\-]{2,23})/i.exec(text);
  return match?.[1];
}

function scoreConfidence(input: {
  items: readonly ParsedReceiptItem[];
  totals: ParsedReceiptTotals;
  computed: number;
  warnings: readonly string[];
  hasMerchant: boolean;
}): number {
  let score = 0.2;
  if (input.hasMerchant) score += 0.2;
  if (input.items.length > 0) score += 0.2;
  if (input.items.length >= 3) score += 0.1;
  if (input.totals.totalMinor > 0) score += 0.15;
  if (input.totals.totalMinor > 0 && Math.abs(input.totals.totalMinor - input.computed) <= 100) {
    score += 0.15;
  }
  score -= input.warnings.length * 0.08;
  return Math.max(0, Math.min(1, Math.round(score * 100) / 100));
}

function safe(value: string, currency: string): number | undefined {
  try {
    const minor = toMinor(value.replace(/,/g, ''), currency);
    return Number.isFinite(minor) ? minor : undefined;
  } catch {
    return undefined;
  }
}
