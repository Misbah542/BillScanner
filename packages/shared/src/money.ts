/**
 * Money in SnapTab is always an integer count of MINOR units (paise for INR,
 * cents for USD). Floating point never touches an amount: `0.1 + 0.2 !== 0.3`
 * is not a rounding curiosity when four people are arguing over a restaurant
 * bill, it is a support ticket.
 *
 * The wire format, the Postgres columns and the Kotlin models all use the same
 * integer. Formatting to "₹2,575.00" happens at the very edge, for display only.
 */

/** Currencies whose minor unit is not 1/100 of the major unit. */
const EXPONENTS: Readonly<Record<string, number>> = {
  BHD: 3,
  IQD: 3,
  JOD: 3,
  KWD: 3,
  OMR: 3,
  TND: 3,
  CLP: 0,
  ISK: 0,
  JPY: 0,
  KRW: 0,
  VND: 0
};

export function minorUnitExponent(currency: string): number {
  return EXPONENTS[currency.toUpperCase()] ?? 2;
}

export function minorUnitsPerMajor(currency: string): number {
  return 10 ** minorUnitExponent(currency);
}

/** `toMinor('2575.00', 'INR') === 257500`. Accepts numbers or decimal strings. */
export function toMinor(amount: string | number, currency = 'INR'): number {
  const exponent = minorUnitExponent(currency);
  const text = typeof amount === 'number' ? amount.toFixed(exponent) : amount.trim();

  const match = /^(-)?(\d+)(?:\.(\d+))?$/.exec(stripGroupSeparators(text));
  if (!match) throw new RangeError(`not a decimal amount: ${JSON.stringify(amount)}`);

  const [, sign, whole, rawFraction = ''] = match;
  const fraction = rawFraction.padEnd(exponent, '0');
  if (fraction.length > exponent) {
    // More precision than the currency has: round half-up on the first extra digit.
    const keep = Number(fraction.slice(0, exponent));
    const next = Number(fraction[exponent]);
    const rounded = keep + (next >= 5 ? 1 : 0);
    const value = Number(whole) * 10 ** exponent + rounded;
    return sign ? -value : value;
  }
  const value = Number(whole) * 10 ** exponent + Number(fraction || '0');
  return sign ? -value : value;
}

/** `fromMinor(257500, 'INR') === '2575.00'` — a plain decimal string, no symbol. */
export function fromMinor(minor: number, currency = 'INR'): string {
  assertInteger(minor);
  const exponent = minorUnitExponent(currency);
  if (exponent === 0) return String(minor);
  const negative = minor < 0;
  const digits = String(Math.abs(minor)).padStart(exponent + 1, '0');
  const whole = digits.slice(0, -exponent);
  const fraction = digits.slice(-exponent);
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}

export function formatMinor(minor: number, currency = 'INR', locale = 'en-IN'): string {
  assertInteger(minor);
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: minorUnitExponent(currency)
  }).format(minor / minorUnitsPerMajor(currency));
}

export function sumMinor(values: readonly number[]): number {
  let total = 0;
  for (const value of values) {
    assertInteger(value);
    total += value;
  }
  return total;
}

/**
 * Splits `totalMinor` in the given weight ratio with no lost or invented minor
 * units, using the largest-remainder (Hamilton) method: floor every share, then
 * hand the leftover units out one at a time, largest fractional part first.
 *
 * `preferIndex` wins ties, which is how the payer absorbs the odd paise.
 */
export function apportion(
  totalMinor: number,
  weights: readonly number[],
  preferIndex = 0
): number[] {
  assertInteger(totalMinor);
  if (weights.length === 0) {
    if (totalMinor !== 0) throw new RangeError('cannot apportion a non-zero total across nobody');
    return [];
  }
  if (weights.some((w) => w < 0)) throw new RangeError('weights cannot be negative');

  const weightTotal = weights.reduce((a, b) => a + b, 0);
  if (weightTotal === 0) {
    // Nobody carries any weight: fall back to an even split.
    return apportion(totalMinor, weights.map(() => 1), preferIndex);
  }

  const sign = totalMinor < 0 ? -1 : 1;
  const magnitude = Math.abs(totalMinor);

  const exact = weights.map((w) => (magnitude * w) / weightTotal);
  const shares = exact.map((value) => Math.floor(value));
  let leftover = magnitude - shares.reduce((a, b) => a + b, 0);

  const order = exact
    .map((value, index) => ({ index, remainder: value - Math.floor(value) }))
    .sort((a, b) => {
      if (b.remainder !== a.remainder) return b.remainder - a.remainder;
      if (a.index === preferIndex) return -1;
      if (b.index === preferIndex) return 1;
      return a.index - b.index;
    });

  for (let i = 0; leftover > 0; i = (i + 1) % order.length) {
    const slot = order[i]!;
    if (weights[slot.index]! === 0) {
      // Never push a unit onto someone with no weight unless everyone has none.
      if (order.every((o) => weights[o.index] === 0)) {
        shares[slot.index]! += 1;
        leftover -= 1;
      }
      continue;
    }
    shares[slot.index]! += 1;
    leftover -= 1;
  }

  return sign === -1 ? shares.map((s) => -s) : shares;
}

/**
 * Amounts reach us written every which way: `2575.00`, `1,300.00`,
 * `2,57,500.00` (Indian lakh grouping), `1.300,00` (European). Work out which
 * separator is the decimal point, drop the rest, and hand back a plain
 * `1234.56`.
 */
function stripGroupSeparators(input: string): string {
  const text = input.replace(/[\s\u00A0_]/g, '');
  const lastDot = text.lastIndexOf('.');
  const lastComma = text.lastIndexOf(',');

  if (lastDot >= 0 && lastComma >= 0) {
    // Whichever comes last is the decimal separator.
    return lastDot > lastComma
      ? text.replace(/,/g, '')
      : text.replace(/\./g, '').replace(',', '.');
  }
  if (lastComma >= 0) {
    // A lone comma with exactly two digits after it and none of the grouping
    // shape before it is a European decimal comma: `12,50`.
    const isDecimalComma = /^-?\d{1,3},\d{2}$/.test(text) && !/^-?\d{1,3},\d{3}$/.test(text);
    return isDecimalComma ? text.replace(',', '.') : text.replace(/,/g, '');
  }
  if ((text.match(/\./g) ?? []).length > 1) {
    // `1.300.000` — every dot is a group separator.
    return text.replace(/\./g, '');
  }
  return text;
}

function assertInteger(value: number): void {
  if (!Number.isInteger(value)) {
    throw new RangeError(`money must be an integer number of minor units, got ${value}`);
  }
}
