import taxonomy from '../data/categories.json' with { type: 'json' };

export type CategoryKind = 'SPEND' | 'INCOME' | 'TRANSFER';

export interface CategoryDefinition {
  slug: string;
  name: string;
  icon: string;
  color: string;
  tintLight: string;
  kind: CategoryKind;
  keywords: string[];
  itemKeywords: string[];
}

export const CATEGORIES: readonly CategoryDefinition[] = taxonomy.categories as CategoryDefinition[];
export const CATEGORY_TAXONOMY_VERSION: number = taxonomy.version;
export const FALLBACK_CATEGORY_SLUG = 'other';

const BY_SLUG = new Map(CATEGORIES.map((c) => [c.slug, c]));

export function categoryBySlug(slug: string): CategoryDefinition | undefined {
  return BY_SLUG.get(slug);
}

/**
 * Merchant strings off a receipt header or a bank SMS are noisy: `SOCIAL
 * OFFLINE BANDRA W MUM`, `UPI/SWIGGY*ORDER/HDFC`, `AMAZON.IN-PMTS`. Normalising
 * strips the payment-rail decoration so the same shop matches itself across
 * channels and so a user's history lookup actually hits.
 */
export function normalizeMerchant(raw: string): string {
  let text = raw.toLowerCase();
  text = text.replace(/\s+/g, ' ').trim();
  // Drop payment-rail prefixes and suffixes.
  text = text.replace(/^(upi|pos|imps|neft|ach|nach|ecs|atw|vps|mmt|bil|inf)[/\-* ]+/g, '');
  // Card narrations use '*' to append a descriptor: SWIGGY*ORDER, AMAZON*MKTPLACE.
  if (text.includes('*')) text = text.split('*')[0] ?? text;
  text = text.replace(/[/\-*](upi|pos|hdfc|icici|sbi|axis|kotak|ybl|okhdfcbank|okaxis|oksbi|paytm|ibl|apl)\b.*$/g, '');
  text = text.replace(/\b(pvt|private|ltd|limited|llp|inc|corp|co|company|india|in|pmts|payments|online|store|stores|retail|enterprises)\b/g, ' ');
  // Trailing city / branch noise and reference numbers.
  text = text.replace(/\b\d{4,}\b/g, ' ');
  text = text.replace(/[^a-z0-9 &']/g, ' ');
  return text.replace(/\s+/g, ' ').trim();
}

export interface CategorySuggestion {
  slug: string;
  /** 0..1. Not a calibrated probability — a ranking score the UI shows as a percentage. */
  confidence: number;
  /** Why this was suggested, so the app can say "matched on 'service charge'". */
  reasons: string[];
}

export interface ClassifyInput {
  merchant?: string | null;
  /** Line-item names off the receipt, if there was one. */
  itemNames?: readonly string[];
  /** Raw SMS or notification text, when the expense came from a bank alert. */
  alertText?: string | null;
  direction?: 'DEBIT' | 'CREDIT';
  /**
   * What this user has picked before for this merchant, strongest signal there
   * is: `{ restaurant: 6 }` means they filed this merchant under Restaurants
   * six times.
   */
  history?: Readonly<Record<string, number>>;
}

/**
 * Rule-based classifier: keyword hits on the merchant, then the item names, then
 * the alert text, with the user's own history for this merchant weighted above
 * all of it. Deliberately explainable — every suggestion carries its reasons,
 * and the UI shows them.
 *
 * Returns suggestions best-first; always at least one entry.
 */
export function classify(input: ClassifyInput, limit = 4): CategorySuggestion[] {
  const merchant = input.merchant ? normalizeMerchant(input.merchant) : '';
  const items = (input.itemNames ?? []).map((n) => n.toLowerCase());
  const alert = (input.alertText ?? '').toLowerCase();

  const scores = new Map<string, number>();
  const reasons = new Map<string, string[]>();

  const bump = (slug: string, amount: number, reason: string) => {
    scores.set(slug, (scores.get(slug) ?? 0) + amount);
    const list = reasons.get(slug) ?? [];
    if (!list.includes(reason) && list.length < 4) list.push(reason);
    reasons.set(slug, list);
  };

  for (const category of CATEGORIES) {
    if (category.slug === FALLBACK_CATEGORY_SLUG) continue;

    for (const keyword of category.keywords) {
      if (merchant && merchant.includes(keyword)) {
        // A keyword that is most of the merchant name is a much better signal
        // than one that happens to appear inside a longer string.
        const coverage = keyword.length / Math.max(merchant.length, 1);
        bump(category.slug, 3 + coverage * 2, `merchant looks like “${keyword}”`);
      } else if (alert && alert.includes(keyword)) {
        bump(category.slug, 1.4, `alert mentions “${keyword}”`);
      }
    }

    for (const keyword of category.itemKeywords) {
      const hits = items.filter((name) => name.includes(keyword)).length;
      if (hits > 0) bump(category.slug, Math.min(hits, 3) * 0.9, `items include “${keyword}”`);
    }
  }

  // A credit is income or a transfer unless something says otherwise.
  if (input.direction === 'CREDIT') {
    bump('income', 2.2, 'money came in rather than out');
    for (const slug of [...scores.keys()]) {
      const category = BY_SLUG.get(slug);
      if (category && category.kind === 'SPEND') scores.set(slug, scores.get(slug)! * 0.35);
    }
  }

  // The user's own past choices for this merchant outrank every keyword table.
  for (const [slug, hits] of Object.entries(input.history ?? {})) {
    if (!BY_SLUG.has(slug) || hits <= 0) continue;
    bump(slug, 4 + Math.min(hits, 8) * 0.9, `you filed this merchant here ${hits}× before`);
  }

  if (scores.size === 0) {
    return [{ slug: FALLBACK_CATEGORY_SLUG, confidence: 0, reasons: ['nothing matched'] }];
  }

  const ranked = [...scores.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const top = ranked[0]![1];

  return ranked.slice(0, limit).map(([slug, score]) => ({
    slug,
    // Normalised against the leader and squashed, so a lone weak match does not
    // present itself as a confident answer.
    confidence: round2(Math.min(1, (score / Math.max(top, 1)) * Math.min(1, top / 5))),
    reasons: reasons.get(slug) ?? []
  }));
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}
