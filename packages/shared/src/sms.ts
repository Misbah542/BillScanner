import rules from '../data/sms-rules.json' with { type: 'json' };
import { toMinor } from './money.js';
import { normalizeMerchant } from './categories.js';

export type TxnDirection = 'DEBIT' | 'CREDIT';
export type AccountKind = 'ACCOUNT' | 'DEBIT_CARD' | 'CREDIT_CARD' | 'WALLET' | 'UNKNOWN';

export interface ParsedAlert {
  direction: TxnDirection;
  amountMinor: number;
  currency: string;
  /** Merchant exactly as the bank wrote it, for showing back to the user. */
  merchantRaw?: string;
  /** Normalised for matching and history lookups. */
  merchantNormalized?: string;
  /** Last 3–6 digits only. SnapTab never keeps a full account number. */
  accountMask?: string;
  accountKind: AccountKind;
  bankId?: string;
  bankName?: string;
  referenceNumber?: string;
  balanceMinor?: number;
  /** 0..1, how much of the message we actually understood. */
  confidence: number;
  /**
   * Stable hash input for de-duplication: the same alert re-read after a
   * permission re-grant, or delivered twice, must not become two expenses.
   */
  fingerprint: string;
}

export const SMS_RULES_VERSION: number = rules.version;

const compiled = {
  reject: rules.reject.map((p) => new RegExp(p, 'i')),
  debit: rules.debitMarkers.map((p) => new RegExp(p, 'i')),
  credit: rules.creditMarkers.map((p) => new RegExp(p, 'i')),
  amount: rules.amount.map((p) => new RegExp(p, 'i')),
  account: rules.account.map((p) => new RegExp(p, 'i')),
  merchant: rules.merchant.map((p) => new RegExp(p, 'i')),
  reference: rules.reference.map((p) => new RegExp(p, 'i')),
  balance: rules.balance.map((p) => new RegExp(p, 'i')),
  senderShape: new RegExp(rules.senderShape),
  cardKind: Object.entries(rules.cardKindHints).map(
    ([kind, patterns]) => [kind as AccountKind, patterns.map((p) => new RegExp(p, 'i'))] as const
  )
};

export interface ParseAlertOptions {
  /** SMS sender ID, e.g. `VM-HDFCBK`. Improves bank detection a lot when present. */
  sender?: string | null;
  /** When the message arrived, used for the fingerprint. */
  receivedAt?: Date;
  currency?: string;
}

/**
 * Parses one bank SMS or payment notification. Returns `null` when the message
 * is not a completed transaction — an OTP, a due-date reminder, a promo, a
 * collect request, a failed payment. Being conservative here matters: a false
 * positive silently invents an expense in someone's month.
 */
export function parseBankAlert(body: string, options: ParseAlertOptions = {}): ParsedAlert | null {
  if (!body || body.trim().length < 12) return null;
  const text = body.replace(/\s+/g, ' ').trim();

  for (const pattern of compiled.reject) {
    if (pattern.test(text)) return null;
  }

  const debit = compiled.debit.some((p) => p.test(text));
  const credit = compiled.credit.some((p) => p.test(text));
  if (!debit && !credit) return null;
  // "Sent Rs.500 ... credited to beneficiary" — the debit verb about *your*
  // account wins, since that is the side we are recording.
  const direction: TxnDirection = debit ? 'DEBIT' : 'CREDIT';

  const currency = options.currency ?? 'INR';
  const amountText = firstGroup(text, compiled.amount, 'amount');
  if (!amountText) return null;

  let amountMinor: number;
  try {
    amountMinor = toMinor(amountText.replace(/,/g, ''), currency);
  } catch {
    return null;
  }
  if (amountMinor <= 0) return null;

  const accountMask = firstGroup(text, compiled.account, 'mask');
  const merchantRaw = cleanMerchant(firstGroup(text, compiled.merchant, 'merchant'));
  const referenceNumber = firstGroup(text, compiled.reference, 'ref');
  const balanceText = firstGroup(text, compiled.balance, 'balance');

  let accountKind: AccountKind = 'UNKNOWN';
  for (const [kind, patterns] of compiled.cardKind) {
    if (patterns.some((p) => p.test(text))) {
      accountKind = kind;
      break;
    }
  }

  const bank = detectBank(text, options.sender);

  // Confidence is just "how many of the five useful fields did we get".
  let understood = 2; // direction + amount, already known good
  if (accountMask) understood += 1;
  if (merchantRaw) understood += 1;
  if (referenceNumber) understood += 0.5;
  if (bank) understood += 0.5;
  const confidence = Math.round(Math.min(1, understood / 5) * 100) / 100;

  return {
    direction,
    amountMinor,
    currency,
    ...(merchantRaw ? { merchantRaw, merchantNormalized: normalizeMerchant(merchantRaw) } : {}),
    ...(accountMask ? { accountMask } : {}),
    accountKind,
    ...(bank ? { bankId: bank.id, bankName: bank.name } : {}),
    ...(referenceNumber ? { referenceNumber } : {}),
    ...(balanceText ? { balanceMinor: safeMinor(balanceText, currency) } : {}),
    confidence,
    fingerprint: fingerprintOf({
      direction,
      amountMinor,
      accountMask,
      referenceNumber,
      merchantNormalized: merchantRaw ? normalizeMerchant(merchantRaw) : undefined,
      receivedAt: options.receivedAt
    })
  };
}

/**
 * De-duplication key. A reference number makes it exact; without one we fall
 * back to amount + account + merchant + the day, which is enough in practice and
 * never merges two genuinely different transactions on different days.
 */
export function fingerprintOf(parts: {
  direction: TxnDirection;
  amountMinor: number;
  accountMask?: string;
  referenceNumber?: string;
  merchantNormalized?: string;
  receivedAt?: Date;
}): string {
  const day = (parts.receivedAt ?? new Date()).toISOString().slice(0, 10);
  const key = parts.referenceNumber
    ? ['ref', parts.referenceNumber.toLowerCase(), parts.amountMinor].join('|')
    : [
        'heur',
        parts.direction,
        parts.amountMinor,
        parts.accountMask ?? '-',
        parts.merchantNormalized ?? '-',
        day
      ].join('|');
  return djb2(key);
}

function detectBank(text: string, sender?: string | null): { id: string; name: string } | null {
  const haystacks: string[] = [];
  if (sender) {
    const shaped = compiled.senderShape.exec(sender.toUpperCase());
    haystacks.push((shaped?.groups?.token ?? sender).toLowerCase());
    haystacks.push(sender.toLowerCase());
  }
  haystacks.push(text.toLowerCase());

  for (const haystack of haystacks) {
    for (const bank of rules.banks) {
      if (bank.tokens.some((token) => haystack.includes(token))) {
        return { id: bank.id, name: bank.name };
      }
    }
  }
  return null;
}

function firstGroup(text: string, patterns: RegExp[], group: string): string | undefined {
  for (const pattern of patterns) {
    const match = pattern.exec(text);
    const value = match?.groups?.[group];
    if (value && value.trim()) return value.trim();
  }
  return undefined;
}

function cleanMerchant(raw?: string): string | undefined {
  if (!raw) return undefined;
  let text = raw.trim().replace(/[.,;:\-*\s]+$/, '');
  // A bare date or amount caught by a greedy pattern is not a merchant.
  if (/^\d[\d\s.,/-]*$/.test(text)) return undefined;
  text = text.replace(/\s+/g, ' ');
  if (text.length < 3 || text.length > 64) return undefined;
  return text;
}

function safeMinor(value: string, currency: string): number | undefined {
  try {
    return toMinor(value.replace(/,/g, ''), currency);
  } catch {
    return undefined;
  }
}

function djb2(input: string): string {
  let hash = 5381;
  for (let i = 0; i < input.length; i += 1) {
    hash = ((hash << 5) + hash + input.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}
