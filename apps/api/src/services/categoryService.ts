import type { Prisma, PrismaClient } from '@prisma/client';
import { CATEGORIES, FALLBACK_CATEGORY_SLUG, classify, normalizeMerchant } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';

type Db = PrismaClient | Prisma.TransactionClient;

export interface Suggestion {
  slug: string;
  name: string;
  colorHex: string;
  tintHex: string;
  confidence: number;
  reasons: string[];
}

/**
 * Suggestions come from the shared rule tables plus this user's own history for
 * the merchant — which is the signal that actually makes the feature feel smart
 * after a couple of weeks of use.
 */
export async function suggestCategories(
  userId: string,
  input: {
    merchant?: string | null;
    itemNames?: string[];
    alertText?: string | null;
    direction?: 'DEBIT' | 'CREDIT';
  },
  limit = 4,
  db: Db = prisma
): Promise<Suggestion[]> {
  const history = input.merchant ? await merchantHistory(userId, input.merchant, db) : {};

  const ranked = classify(
    {
      merchant: input.merchant ?? null,
      itemNames: input.itemNames ?? [],
      alertText: input.alertText ?? null,
      ...(input.direction ? { direction: input.direction } : {}),
      history
    },
    limit
  );

  const rows = await db.category.findMany({
    where: { slug: { in: ranked.map((r) => r.slug) }, ownerId: null }
  });
  const bySlug = new Map(rows.map((row) => [row.slug, row]));

  return ranked.map((suggestion) => {
    const row = bySlug.get(suggestion.slug);
    const fallback = CATEGORIES.find((c) => c.slug === suggestion.slug);
    return {
      slug: suggestion.slug,
      name: row?.name ?? fallback?.name ?? suggestion.slug,
      colorHex: row?.colorHex ?? fallback?.color ?? '#8C867B',
      tintHex: row?.tintHex ?? fallback?.tintLight ?? '#F0EBE0',
      confidence: suggestion.confidence,
      reasons: suggestion.reasons
    };
  });
}

async function merchantHistory(
  userId: string,
  merchant: string,
  db: Db
): Promise<Record<string, number>> {
  const normalized = normalizeMerchant(merchant);
  if (!normalized) return {};
  const rows = await db.merchantCategoryMemory.findMany({
    where: { userId, merchantNormalized: normalized },
    include: { category: { select: { slug: true } } }
  });
  return Object.fromEntries(rows.map((row) => [row.category.slug, row.hits]));
}

/** Resolves a slug to a category row, falling back to Uncategorised. */
export async function resolveCategoryId(
  slug: string | undefined | null,
  userId: string,
  db: Db = prisma
): Promise<string | null> {
  if (!slug) return null;
  const own = await db.category.findFirst({ where: { slug, ownerId: userId } });
  if (own) return own.id;
  const shared = await db.category.findFirst({ where: { slug, ownerId: null } });
  if (shared) return shared.id;
  const fallback = await db.category.findFirst({ where: { slug: FALLBACK_CATEGORY_SLUG, ownerId: null } });
  return fallback?.id ?? null;
}

/**
 * Records that this user filed this merchant under this category. Called only when
 * the choice was the user's — accepting a suggestion unchanged is weaker evidence
 * and would otherwise let one wrong guess entrench itself.
 */
export async function rememberMerchantCategory(
  userId: string,
  merchant: string | null | undefined,
  categoryId: string | null,
  db: Db = prisma
): Promise<void> {
  if (!merchant || !categoryId) return;
  const merchantNormalized = normalizeMerchant(merchant);
  if (!merchantNormalized) return;

  await db.merchantCategoryMemory.upsert({
    where: { userId_merchantNormalized_categoryId: { userId, merchantNormalized, categoryId } },
    create: { userId, merchantNormalized, categoryId, hits: 1 },
    update: { hits: { increment: 1 }, lastSeenAt: new Date() }
  });
}
