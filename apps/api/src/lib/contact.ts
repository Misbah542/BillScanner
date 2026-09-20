import { ApiError } from './errors.js';

/**
 * People get added to a split by email or by phone, typed by hand, often with a
 * space or a leading zero. Everything is normalised to one canonical form before
 * it touches the database, so `+91 98200 11234`, `098200 11234` and
 * `919820011234` all resolve to the same person.
 */

export type ContactKind = 'EMAIL' | 'PHONE';

export interface NormalizedContact {
  kind: ContactKind;
  /** Lower-cased email, or E.164 phone including the `+`. */
  value: string;
}

/** Default country for bare national numbers. India, matching the app's audience. */
const DEFAULT_DIAL_CODE = '91';
const NATIONAL_LENGTHS: Record<string, number> = { '91': 10, '1': 10, '44': 10, '61': 9, '65': 8 };

export function normalizeEmail(raw: string): string {
  const email = raw.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@.]+\.[^\s@]{2,}$/.test(email)) {
    throw ApiError.badRequest('INVALID_EMAIL', `“${raw}” is not an email address.`);
  }
  return email;
}

export function normalizePhone(raw: string, dialCode = DEFAULT_DIAL_CODE): string {
  let digits = raw.trim().replace(/[\s()\-.]/g, '');
  const explicit = digits.startsWith('+');
  digits = digits.replace(/^\+/, '');
  if (!/^\d{6,15}$/.test(digits)) {
    throw ApiError.badRequest('INVALID_PHONE', `“${raw}” is not a phone number.`);
  }

  if (!explicit) {
    // `00` international prefix, `0` national trunk prefix, or a bare national number.
    if (digits.startsWith('00')) {
      digits = digits.slice(2);
    } else if (digits.startsWith('0')) {
      digits = dialCode + digits.replace(/^0+/, '');
    } else if (digits.length === (NATIONAL_LENGTHS[dialCode] ?? 10)) {
      digits = dialCode + digits;
    }
  }

  if (digits.length < 8 || digits.length > 15) {
    throw ApiError.badRequest('INVALID_PHONE', `“${raw}” is not a phone number.`);
  }
  return `+${digits}`;
}

/** Works out whether the user typed an email or a phone number, and normalises it. */
export function normalizeContact(raw: string): NormalizedContact {
  const trimmed = raw.trim();
  if (!trimmed) throw ApiError.badRequest('INVALID_CONTACT', 'Enter an email address or phone number.');
  if (trimmed.includes('@')) return { kind: 'EMAIL', value: normalizeEmail(trimmed) };
  return { kind: 'PHONE', value: normalizePhone(trimmed) };
}

/** `aditi@gmail.com` -> `a***i@gmail.com`; `+919820011234` -> `+91 ••••• 1234`. */
export function maskContact(contact: NormalizedContact): string {
  if (contact.kind === 'EMAIL') {
    const [local = '', domain = ''] = contact.value.split('@');
    const head = local.slice(0, 1);
    const tail = local.length > 2 ? local.slice(-1) : '';
    return `${head}${'*'.repeat(Math.max(1, local.length - 2))}${tail}@${domain}`;
  }
  const digits = contact.value.replace('+', '');
  return `+${digits.slice(0, 2)} ••••• ${digits.slice(-4)}`;
}

/** A display name to show for someone who has not signed in yet. */
export function placeholderName(contact: NormalizedContact): string {
  if (contact.kind === 'EMAIL') {
    const local = contact.value.split('@')[0] ?? contact.value;
    return local
      .split(/[._\-+]/)
      .filter(Boolean)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ')
      .slice(0, 60) || contact.value;
  }
  return maskContact(contact);
}
