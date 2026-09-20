import { Router } from 'express';
import { z } from 'zod';
import { IngestAlertInput, SMS_RULES_VERSION } from '@snaptab/shared';
import { prisma } from '../lib/prisma.js';
import { ApiError } from '../lib/errors.js';
import { requireAuth } from '../middleware/auth.js';
import { validateBody, validateParams, validateQuery } from '../middleware/validate.js';
import { writeLimiter } from '../middleware/rateLimit.js';
import { alertSelect, ingestAlerts } from '../services/alertService.js';
import { suggestCategories } from '../services/categoryService.js';

export const alertsRouter = Router();
alertsRouter.use(requireAuth);

const IdParam = z.object({ id: z.string().uuid() });

const IngestBody = z.object({
  alerts: z.array(IngestAlertInput).min(1).max(100),
  /** Which version of the shared rule file the device parsed with. */
  rulesVersion: z.number().int().optional()
});

/**
 * `POST /v1/alerts` — the phone hands up debits and credits it read.
 *
 * Parsing happens ON THE DEVICE using the same rule file the server uses
 * (packages/shared/data/sms-rules.json), so only the structured fields travel:
 * amount, direction, merchant, last four digits, bank, reference. The message body
 * is sent only if the user opted in.
 *
 * Idempotent on the fingerprint, because a phone that re-reads its inbox after a
 * permission re-grant must not double every expense.
 */
alertsRouter.post('/', writeLimiter, validateBody(IngestBody), async (req, res, next) => {
  try {
    const body = req.body as z.infer<typeof IngestBody>;
    const outcomes = await ingestAlerts(req.user!.id, body.alerts);

    res.status(201).json({
      results: outcomes,
      created: outcomes.filter((outcome) => outcome.created).length,
      duplicates: outcomes.filter((outcome) => !outcome.created).length,
      /** If this is ahead of the device, the app should refresh its rule file. */
      serverRulesVersion: SMS_RULES_VERSION
    });
  } catch (error) {
    next(error);
  }
});

const ListQuery = z.object({
  status: z.enum(['UNMATCHED', 'LINKED', 'SETTLED', 'IGNORED']).optional(),
  direction: z.enum(['DEBIT', 'CREDIT']).optional(),
  limit: z.coerce.number().int().min(1).max(100).default(30),
  cursor: z.string().uuid().optional()
});

/** The inbox. Defaults to everything, newest first; the app filters by status. */
alertsRouter.get('/', validateQuery(ListQuery), async (req, res, next) => {
  try {
    const query = req.query as unknown as z.infer<typeof ListQuery>;
    const userId = req.user!.id;

    const cursorRow = query.cursor
      ? await prisma.bankAlert.findUnique({
          where: { id: query.cursor },
          select: { occurredAt: true, id: true }
        })
      : null;

    const where = {
      userId,
      ...(query.status ? { status: query.status } : {}),
      ...(query.direction ? { direction: query.direction } : {}),
      ...(cursorRow
        ? {
            OR: [
              { occurredAt: { lt: cursorRow.occurredAt } },
              { occurredAt: cursorRow.occurredAt, id: { lt: cursorRow.id } }
            ]
          }
        : {})
    };

    const [rows, counts] = await Promise.all([
      prisma.bankAlert.findMany({
        where,
        orderBy: [{ occurredAt: 'desc' }, { id: 'desc' }],
        take: query.limit + 1,
        select: alertSelect
      }),
      prisma.bankAlert.groupBy({ by: ['status'], where: { userId }, _count: true })
    ]);

    const hasMore = rows.length > query.limit;
    const page = hasMore ? rows.slice(0, query.limit) : rows;

    res.json({
      alerts: page,
      nextCursor: hasMore ? (page[page.length - 1]?.id ?? null) : null,
      counts: Object.fromEntries(counts.map((row) => [row.status, row._count]))
    });
  } catch (error) {
    next(error);
  }
});

/**
 * `POST /v1/alerts/:id/expense` — the one-tap path for a lone debit.
 *
 * Turns the alert into a PERSONAL expense: no bill, no items, no split, straight
 * into this month's spend. This is the "not everything has a bill" case, and it is
 * meant to be a single tap from the notification.
 */
const ToExpenseBody = z.object({
  categorySlug: z.string().trim().max(64).optional(),
  note: z.string().trim().max(2000).optional(),
  /** Set to attribute it to a group without splitting it yet. */
  groupId: z.string().uuid().optional()
});

alertsRouter.post(
  '/:id/expense',
  writeLimiter,
  validateParams(IdParam),
  validateBody(ToExpenseBody),
  async (req, res, next) => {
    try {
      const { id } = req.params as unknown as z.infer<typeof IdParam>;
      const body = req.body as z.infer<typeof ToExpenseBody>;
      const userId = req.user!.id;

      const alert = await prisma.bankAlert.findFirst({
        where: { id, userId },
        include: { suggestedCategory: { select: { id: true, slug: true } } }
      });
      if (!alert) throw ApiError.notFound('That alert');
      if (alert.expenseId) {
        throw ApiError.conflict('ALREADY_LINKED', 'That alert is already on an expense.', {
          expenseId: alert.expenseId
        });
      }
      if (alert.direction === 'CREDIT') {
        throw ApiError.badRequest(
          'CREDIT_NOT_AN_EXPENSE',
          'That was money coming in. Record it as a settlement instead.'
        );
      }

      // Importing the service lazily keeps this module free of a cycle through
      // expenseService -> categoryService -> alertService.
      const { createExpense } = await import('../services/expenseService.js');
      const { serializeExpense } = await import('./expenses.routes.js');

      const expense = await createExpense(userId, {
        kind: 'PERSONAL',
        source: 'ALERT',
        ...(alert.merchantRaw ? { merchantName: alert.merchantRaw } : {}),
        ...(body.note ? { note: body.note } : {}),
        occurredAt: alert.occurredAt,
        currency: alert.currency,
        categorySlug: body.categorySlug ?? alert.suggestedCategory?.slug,
        ...(body.groupId ? { groupId: body.groupId } : {}),
        itemTotalMinor: alert.amountMinor,
        serviceChargeMinor: 0,
        taxMinor: 0,
        discountMinor: 0,
        tipMinor: 0,
        roundOffMinor: 0,
        totalMinor: alert.amountMinor,
        items: [],
        taxLines: [],
        extrasMode: 'PROPORTIONAL',
        alertId: alert.id
      });

      res.status(201).json({ expense: serializeExpense(expense, userId) });
    } catch (error) {
      next(error);
    }
  }
);

/** Attach an alert to an expense that already exists — the scan-then-match path. */
const LinkBody = z.object({ expenseId: z.string().uuid() });

alertsRouter.post('/:id/link', writeLimiter, validateParams(IdParam), validateBody(LinkBody), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const { expenseId } = req.body as z.infer<typeof LinkBody>;
    const userId = req.user!.id;

    const [alert, expense] = await Promise.all([
      prisma.bankAlert.findFirst({ where: { id, userId }, select: { id: true, amountMinor: true } }),
      prisma.expense.findFirst({
        where: { id: expenseId, OR: [{ createdById: userId }, { paidById: userId }] },
        select: { id: true, totalMinor: true }
      })
    ]);
    if (!alert) throw ApiError.notFound('That alert');
    if (!expense) throw ApiError.notFound('That expense');

    const updated = await prisma.bankAlert.update({
      where: { id },
      data: { status: 'LINKED', expenseId },
      select: alertSelect
    });

    res.json({
      alert: updated,
      // Worth telling the user rather than silently accepting a mismatch.
      amountsMatch: alert.amountMinor === expense.totalMinor,
      differenceMinor: expense.totalMinor - alert.amountMinor
    });
  } catch (error) {
    next(error);
  }
});

alertsRouter.post('/:id/ignore', writeLimiter, validateParams(IdParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof IdParam>;
    const result = await prisma.bankAlert.updateMany({
      where: { id, userId: req.user!.id },
      data: { status: 'IGNORED' }
    });
    if (result.count === 0) throw ApiError.notFound('That alert');
    res.status(204).end();
  } catch (error) {
    next(error);
  }
});

const SuggestParam = z.object({ id: z.string().uuid() });

alertsRouter.get('/:id/suggestions', validateParams(SuggestParam), async (req, res, next) => {
  try {
    const { id } = req.params as unknown as z.infer<typeof SuggestParam>;
    const alert = await prisma.bankAlert.findFirst({
      where: { id, userId: req.user!.id },
      select: { merchantRaw: true, rawBody: true, direction: true, amountMinor: true }
    });
    if (!alert) throw ApiError.notFound('That alert');

    const [categories, nearbyExpenses] = await Promise.all([
      suggestCategories(req.user!.id, {
        merchant: alert.merchantRaw,
        alertText: alert.rawBody,
        direction: alert.direction
      }),
      // Expenses of the same amount with no alert attached yet — very likely the
      // bill the user already scanned for this charge.
      prisma.expense.findMany({
        where: {
          createdById: req.user!.id,
          totalMinor: alert.amountMinor,
          alerts: { none: {} },
          status: { not: 'VOID' }
        },
        orderBy: { occurredAt: 'desc' },
        take: 3,
        select: { id: true, merchantName: true, totalMinor: true, occurredAt: true }
      })
    ]);

    res.json({ categories, linkCandidates: nearbyExpenses });
  } catch (error) {
    next(error);
  }
});

/**
 * The device asks whether its copy of the parsing rules is current. Serving the
 * rules from the server is what lets a new bank's SMS format start working without
 * an app release.
 */
alertsRouter.get('/rules/version', (_req, res) => {
  res.json({ version: SMS_RULES_VERSION });
});
