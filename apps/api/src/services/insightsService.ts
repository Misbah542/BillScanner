import type { MonthlySummary, MonthlySummaryQuery } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';

/**
 * "What did I spend this month" has an exact definition here, and it is not
 * "everything I paid for":
 *
 *   spent = personal expenses in full
 *         + YOUR SHARE of shared expenses (whoever paid)
 *
 * Money you fronted for four other people at dinner is not your spending, it is a
 * loan, and it is reported separately as `paidOutMinor` and `owedToYouMinor`. A
 * lone ₹486 Swiggy debit with no bill and nobody to split with is a complete
 * personal expense and lands in `personalMinor`.
 */
export async function monthlySummary(
  userId: string,
  query: MonthlySummaryQuery
): Promise<MonthlySummary> {
  const { start, end, label } = monthRange(query.month, query.timezone);
  const previous = monthRange(shiftMonth(label, -1), query.timezone);

  const currency =
    (await prisma.user.findUnique({ where: { id: userId }, select: { currency: true } }))?.currency ?? 'INR';

  const wantPersonal = query.kind === 'ALL' || query.kind === 'PERSONAL';
  const wantShared = query.kind === 'ALL' || query.kind === 'SHARED';

  const [personal, shared, paidOut, previousPersonal, previousShared] = await Promise.all([
    wantPersonal ? personalExpenses(userId, start, end) : Promise.resolve([]),
    wantShared ? sharedShares(userId, start, end) : Promise.resolve([]),
    paidOutTotal(userId, start, end),
    wantPersonal ? personalExpenses(userId, previous.start, previous.end) : Promise.resolve([]),
    wantShared ? sharedShares(userId, previous.start, previous.end) : Promise.resolve([])
  ]);

  const personalMinor = personal.reduce((total, row) => total + row.totalMinor, 0);
  const sharedShareMinor = shared.reduce((total, row) => total + row.amountMinor, 0);
  const spentMinor = personalMinor + sharedShareMinor;

  const previousSpentMinor =
    previousPersonal.reduce((total, row) => total + row.totalMinor, 0) +
    previousShared.reduce((total, row) => total + row.amountMinor, 0);

  // One bucket per category, mixing both kinds, because "34% restaurants" should
  // count the solo Swiggy order alongside your quarter of Saturday's dinner.
  const buckets = new Map<string, { slug: string; name: string; color: string; spentMinor: number; count: number }>();
  const add = (
    category: { slug: string; name: string; colorHex: string } | null,
    amountMinor: number
  ) => {
    const slug = category?.slug ?? 'other';
    const existing = buckets.get(slug) ?? {
      slug,
      name: category?.name ?? 'Uncategorised',
      color: category?.colorHex ?? '#8C867B',
      spentMinor: 0,
      count: 0
    };
    existing.spentMinor += amountMinor;
    existing.count += 1;
    buckets.set(slug, existing);
  };

  for (const row of personal) add(row.category, row.totalMinor);
  for (const row of shared) add(row.expense.category, row.amountMinor);

  const byCategory = [...buckets.values()]
    .sort((a, b) => b.spentMinor - a.spentMinor)
    .map((bucket) => ({
      slug: bucket.slug,
      name: bucket.name,
      color: bucket.color,
      spentMinor: bucket.spentMinor,
      shareBp: spentMinor > 0 ? Math.round((bucket.spentMinor / spentMinor) * 10_000) : 0,
      count: bucket.count
    }));

  const outstanding = await outstandingForMonth(userId, start, end);

  return {
    month: label,
    currency,
    personalMinor,
    sharedShareMinor,
    spentMinor,
    paidOutMinor: paidOut,
    owedToYouMinor: outstanding.owedToYou,
    owedByYouMinor: outstanding.owedByYou,
    expenseCount: personal.length + shared.length,
    personalCount: personal.length,
    sharedCount: shared.length,
    byCategory,
    previousSpentMinor
  };
}

function personalExpenses(userId: string, start: Date, end: Date) {
  return prisma.expense.findMany({
    where: {
      kind: 'PERSONAL',
      createdById: userId,
      status: { in: ['OPEN', 'SETTLED'] },
      occurredAt: { gte: start, lt: end }
    },
    select: {
      totalMinor: true,
      category: { select: { slug: true, name: true, colorHex: true } }
    }
  });
}

function sharedShares(userId: string, start: Date, end: Date) {
  return prisma.expenseShare.findMany({
    where: {
      userId,
      expense: {
        kind: 'SHARED',
        status: { in: ['OPEN', 'SETTLED'] },
        occurredAt: { gte: start, lt: end }
      }
    },
    select: {
      amountMinor: true,
      expense: { select: { category: { select: { slug: true, name: true, colorHex: true } } } }
    }
  });
}

/** Everything that actually left your account this month, shares of others included. */
async function paidOutTotal(userId: string, start: Date, end: Date): Promise<number> {
  const result = await prisma.expense.aggregate({
    where: {
      paidById: userId,
      status: { in: ['OPEN', 'SETTLED'] },
      occurredAt: { gte: start, lt: end }
    },
    _sum: { totalMinor: true }
  });
  return result._sum.totalMinor ?? 0;
}

async function outstandingForMonth(userId: string, start: Date, end: Date) {
  const [theirs, mine] = await Promise.all([
    prisma.expenseShare.findMany({
      where: {
        userId: { not: userId },
        settledAt: null,
        expense: { paidById: userId, kind: 'SHARED', status: 'OPEN', occurredAt: { gte: start, lt: end } }
      },
      select: { amountMinor: true, paidMinor: true }
    }),
    prisma.expenseShare.findMany({
      where: {
        userId,
        settledAt: null,
        expense: { paidById: { not: userId }, kind: 'SHARED', status: 'OPEN', occurredAt: { gte: start, lt: end } }
      },
      select: { amountMinor: true, paidMinor: true }
    })
  ]);

  return {
    owedToYou: theirs.reduce((total, row) => total + row.amountMinor - row.paidMinor, 0),
    owedByYou: mine.reduce((total, row) => total + row.amountMinor - row.paidMinor, 0)
  };
}

/**
 * Month boundaries in the user's own timezone. Getting this wrong puts a 1 a.m.
 * Swiggy order in the wrong month, which is exactly the kind of thing that makes
 * people stop trusting the number.
 */
export function monthRange(
  month: string | undefined,
  timezone: string
): { start: Date; end: Date; label: string } {
  const label = month ?? currentMonthLabel(timezone);
  const [yearText, monthText] = label.split('-');
  const year = Number(yearText);
  const monthIndex = Number(monthText) - 1;

  const start = zonedMonthStart(year, monthIndex, timezone);
  const end = zonedMonthStart(monthIndex === 11 ? year + 1 : year, (monthIndex + 1) % 12, timezone);
  return { start, end, label };
}

function currentMonthLabel(timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit'
  }).formatToParts(new Date());
  const year = parts.find((p) => p.type === 'year')?.value ?? '1970';
  const month = parts.find((p) => p.type === 'month')?.value ?? '01';
  return `${year}-${month}`;
}

/**
 * Midnight on the 1st of the given month in `timezone`, as a UTC instant. Works
 * out the zone's offset at that moment rather than assuming a fixed one, so it
 * stays correct across DST for the zones that have it.
 */
function zonedMonthStart(year: number, monthIndex: number, timezone: string): Date {
  const guess = Date.UTC(year, monthIndex, 1, 0, 0, 0);
  const offset = zoneOffsetMs(new Date(guess), timezone);
  const corrected = guess - offset;
  // One more pass, in case the first correction crossed a DST boundary.
  return new Date(corrected - (zoneOffsetMs(new Date(corrected), timezone) - offset));
}

function zoneOffsetMs(instant: Date, timezone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).formatToParts(instant);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? '0');
  const asUtc = Date.UTC(
    get('year'),
    get('month') - 1,
    get('day'),
    get('hour'),
    get('minute'),
    get('second')
  );
  return asUtc - instant.getTime();
}

function shiftMonth(label: string, delta: number): string {
  const [yearText, monthText] = label.split('-');
  const date = new Date(Date.UTC(Number(yearText), Number(monthText) - 1 + delta, 1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}
