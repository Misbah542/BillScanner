import { Router } from 'express';
import { z } from 'zod';
import { MonthlySummaryQuery } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { requireAuth } from '../middleware/auth.js';
import { validateQuery } from '../middleware/validate.js';
import { monthRange, monthlySummary } from '../services/insightsService.js';

export const insightsRouter = Router();
insightsRouter.use(requireAuth);

/**
 * `GET /v1/insights/monthly` — the number the home screen leads with.
 *
 * `spentMinor` is personal expenses in full plus your share of shared ones. Money
 * you fronted for other people is reported separately (`paidOutMinor`,
 * `owedToYouMinor`) rather than folded into your spending, because it is a loan and
 * not an expense.
 */
insightsRouter.get('/monthly', validateQuery(MonthlySummaryQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof MonthlySummaryQuery>;
    const timezone = query.timezone === 'Asia/Kolkata' ? req.user!.timezone : query.timezone;
    const summary = await monthlySummary(req.user!.id, { ...query, timezone });
    res.json({ summary });
  } catch (error) {
    next(error);
  }
});

const TrendQuery = z.object({
  months: z.coerce.number().int().min(2).max(24).default(6),
  timezone: z.string().max(64).optional(),
  kind: z.enum(['ALL', 'PERSONAL', 'SHARED']).default('ALL')
});

/** A short history for the spend chart: one figure per month, oldest first. */
insightsRouter.get('/trend', validateQuery(TrendQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof TrendQuery>;
    const timezone = query.timezone ?? req.user!.timezone;

    const labels: string[] = [];
    const now = new Date();
    for (let back = query.months - 1; back >= 0; back -= 1) {
      const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - back, 1));
      labels.push(`${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`);
    }

    const points = await Promise.all(
      labels.map(async (month) => {
        const summary = await monthlySummary(req.user!.id, { month, timezone, kind: query.kind });
        return {
          month,
          spentMinor: summary.spentMinor,
          personalMinor: summary.personalMinor,
          sharedShareMinor: summary.sharedShareMinor,
          expenseCount: summary.expenseCount
        };
      })
    );

    res.json({ currency: req.user!.currency, points });
  } catch (error) {
    next(error);
  }
});

const MerchantQuery = z.object({
  month: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).optional(),
  limit: z.coerce.number().int().min(1).max(50).default(10)
});

/** Where the money actually went, merchant by merchant. */
insightsRouter.get('/merchants', validateQuery(MerchantQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof MerchantQuery>;
    const { start, end } = monthRange(query.month, req.user!.timezone);

    const rows = await prisma.expense.groupBy({
      by: ['merchantNormalized'],
      where: {
        createdById: req.user!.id,
        status: { in: ['OPEN', 'SETTLED'] },
        occurredAt: { gte: start, lt: end },
        merchantNormalized: { not: null }
      },
      _sum: { totalMinor: true },
      _count: true,
      orderBy: { _sum: { totalMinor: 'desc' } },
      take: query.limit
    });

    // groupBy loses the pretty name, so fetch one display name per merchant.
    const names = await Promise.all(
      rows.map((row) =>
        prisma.expense.findFirst({
          where: { createdById: req.user!.id, merchantNormalized: row.merchantNormalized },
          orderBy: { occurredAt: 'desc' },
          select: { merchantName: true, category: { select: { slug: true, name: true, colorHex: true } } }
        })
      )
    );

    res.json({
      merchants: rows.map((row, index) => ({
        merchant: names[index]?.merchantName ?? row.merchantNormalized,
        category: names[index]?.category ?? null,
        spentMinor: row._sum.totalMinor ?? 0,
        count: row._count
      }))
    });
  } catch (error) {
    next(error);
  }
});
