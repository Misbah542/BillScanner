import { z } from 'zod';

/**
 * The single spine of the app is an EXPENSE, not a bill.
 *
 * Plenty of spending has no bill to scan and nobody to split with: a ₹486 Swiggy
 * debit that SnapTab read off an SMS is a complete expense on its own. So an
 * expense is `PERSONAL` (all yours, may have no items at all) or `SHARED` (split
 * with people), and both count toward your monthly spend.
 */
export const ExpenseKind = z.enum(['PERSONAL', 'SHARED']);
export type ExpenseKind = z.infer<typeof ExpenseKind>;

/** Where the expense came from. `ALERT` expenses often have no items. */
export const ExpenseSource = z.enum(['SCAN', 'ALERT', 'MANUAL', 'IMPORT']);
export type ExpenseSource = z.infer<typeof ExpenseSource>;

export const ExpenseStatus = z.enum(['DRAFT', 'OPEN', 'SETTLED', 'VOID']);
export type ExpenseStatus = z.infer<typeof ExpenseStatus>;

export const SplitMethodSchema = z.enum(['EQUAL', 'EXACT', 'PERCENT', 'SHARES', 'ITEMIZED']);

export const CategorySourceSchema = z.enum(['USER', 'SUGGESTED', 'RULE', 'DEFAULT']);
export type CategorySource = z.infer<typeof CategorySourceSchema>;

export const TxnDirectionSchema = z.enum(['DEBIT', 'CREDIT']);
export const AlertChannelSchema = z.enum(['SMS', 'NOTIFICATION', 'MANUAL']);
export const AlertStatusSchema = z.enum(['UNMATCHED', 'LINKED', 'IGNORED']);

export const SettlementMethodSchema = z.enum(['UPI', 'CASH', 'BANK_TRANSFER', 'CARD', 'OTHER']);
export const SettlementStatusSchema = z.enum(['PENDING', 'CONFIRMED', 'REJECTED']);

export const GroupRoleSchema = z.enum(['OWNER', 'ADMIN', 'MEMBER']);
export const InviteStatusSchema = z.enum(['PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED']);

/** Money on the wire is always an integer of minor units. Never a float, never a string. */
export const MinorAmount = z
  .number()
  .int('amounts are whole minor units (paise), not decimals')
  .finite();

export const NonNegativeMinor = MinorAmount.min(0);

export const CurrencyCode = z
  .string()
  .regex(/^[A-Z]{3}$/, 'currency must be a 3-letter ISO 4217 code')
  .default('INR');

/** A loose but useful E.164 check. Normalisation happens server-side. */
export const PhoneNumber = z
  .string()
  .trim()
  .regex(/^\+?[1-9]\d{6,14}$/, 'not a usable phone number');

export const EmailAddress = z.string().trim().toLowerCase().email();

export const ExpenseItemInput = z.object({
  name: z.string().trim().min(1).max(200),
  /** Thousandths of a unit: 1000 = one, 500 = half a kilo. */
  quantityMilli: z.number().int().positive().default(1000),
  unitPriceMinor: NonNegativeMinor,
  amountMinor: NonNegativeMinor,
  categoryHint: z.string().trim().max(64).optional()
});
export type ExpenseItemInput = z.infer<typeof ExpenseItemInput>;

export const TaxLineInput = z.object({
  label: z.string().trim().min(1).max(64),
  kind: z.enum(['SERVICE_CHARGE', 'CGST', 'SGST', 'IGST', 'VAT', 'CESS', 'OTHER']),
  rateBp: z.number().int().min(0).max(100_000).optional(),
  amountMinor: MinorAmount
});

export const SplitParticipantInput = z.object({
  userId: z.string().uuid().optional(),
  /** Either a known user, or someone identified only by contact — invited on the fly. */
  email: EmailAddress.optional(),
  phone: PhoneNumber.optional(),
  displayName: z.string().trim().min(1).max(120).optional(),
  /** EXACT: minor units. PERCENT: basis points. SHARES: share count. */
  value: z.number().int().min(0).optional()
});

export const ItemAssignmentInput = z.object({
  itemId: z.string().uuid(),
  assignments: z
    .array(
      z.object({
        userId: z.string().uuid(),
        weight: z.number().int().min(0).max(1000).default(1)
      })
    )
    .min(1)
});

/**
 * Creating an expense. A personal one needs almost nothing — amount, merchant,
 * done. A shared one adds a split.
 */
export const CreateExpenseInput = z
  .object({
    kind: ExpenseKind.default('PERSONAL'),
    source: ExpenseSource.default('MANUAL'),
    groupId: z.string().uuid().optional(),
    merchantName: z.string().trim().min(1).max(200).optional(),
    note: z.string().trim().max(2000).optional(),
    occurredAt: z.coerce.date().optional(),
    currency: CurrencyCode,
    categorySlug: z.string().trim().max(64).optional(),
    paidByUserId: z.string().uuid().optional(),

    itemTotalMinor: NonNegativeMinor.optional(),
    serviceChargeMinor: MinorAmount.default(0),
    taxMinor: MinorAmount.default(0),
    discountMinor: NonNegativeMinor.default(0),
    tipMinor: MinorAmount.default(0),
    roundOffMinor: MinorAmount.default(0),
    /** Required. For an alert-only personal expense this is the whole story. */
    totalMinor: NonNegativeMinor,

    items: z.array(ExpenseItemInput).max(300).default([]),
    taxLines: z.array(TaxLineInput).max(30).default([]),

    splitMethod: SplitMethodSchema.optional(),
    participants: z.array(SplitParticipantInput).max(60).optional(),
    itemAssignments: z.array(ItemAssignmentInput).max(300).optional(),
    extrasMode: z.enum(['PROPORTIONAL', 'EQUAL']).default('PROPORTIONAL'),

    scanId: z.string().uuid().optional(),
    /** Link the bank alert this expense came from, so it stops asking. */
    alertId: z.string().uuid().optional(),
    receiptAssetKey: z.string().max(512).optional()
  })
  .superRefine((value, ctx) => {
    if (value.kind === 'SHARED') {
      if (!value.splitMethod) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['splitMethod'],
          message: 'a shared expense needs a split method'
        });
      }
      if (value.splitMethod !== 'ITEMIZED' && (value.participants?.length ?? 0) < 1) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['participants'],
          message: 'a shared expense needs at least one participant'
        });
      }
      if (value.splitMethod === 'ITEMIZED' && (value.itemAssignments?.length ?? 0) < 1) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['itemAssignments'],
          message: 'an itemised split needs item assignments'
        });
      }
    }
    if (value.kind === 'PERSONAL' && value.participants && value.participants.length > 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['participants'],
        message: 'a personal expense has no participants — set kind to SHARED to split it'
      });
    }
    if (value.items.length > 0) {
      const sum = value.items.reduce((a, i) => a + i.amountMinor, 0);
      const stated = value.itemTotalMinor ?? sum;
      if (Math.abs(stated - sum) > 100) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['itemTotalMinor'],
          message: `items add up to ${sum} but itemTotalMinor says ${stated}`
        });
      }
    }
  });
export type CreateExpenseInput = z.infer<typeof CreateExpenseInput>;

/** A parsed bank alert on its way up from the phone. The body never comes with it. */
export const IngestAlertInput = z.object({
  direction: TxnDirectionSchema,
  amountMinor: NonNegativeMinor,
  currency: CurrencyCode,
  merchantRaw: z.string().trim().max(200).optional(),
  accountMask: z.string().trim().regex(/^\d{3,6}$/).optional(),
  accountKind: z.enum(['ACCOUNT', 'DEBIT_CARD', 'CREDIT_CARD', 'WALLET', 'UNKNOWN']).default('UNKNOWN'),
  bankId: z.string().trim().max(32).optional(),
  bankName: z.string().trim().max(120).optional(),
  referenceNumber: z.string().trim().max(64).optional(),
  channel: AlertChannelSchema.default('SMS'),
  occurredAt: z.coerce.date(),
  /** Computed on the device with the shared rules; the server de-duplicates on it. */
  fingerprint: z.string().trim().min(4).max(64),
  confidence: z.number().min(0).max(1).default(0),
  /** Opt-in only, off by default. Kept for "why did you read it that way?" support. */
  rawBody: z.string().max(2000).optional()
});
export type IngestAlertInput = z.infer<typeof IngestAlertInput>;

export const MonthlySummaryQuery = z.object({
  /** `2026-09`. Defaults to the current month in the user's timezone. */
  month: z
    .string()
    .regex(/^\d{4}-(0[1-9]|1[0-2])$/, 'month must look like 2026-09')
    .optional(),
  timezone: z.string().max(64).default('Asia/Kolkata'),
  kind: z.enum(['ALL', 'PERSONAL', 'SHARED']).default('ALL')
});
export type MonthlySummaryQuery = z.infer<typeof MonthlySummaryQuery>;

/**
 * What "you spent this month" means, spelled out: your personal expenses in
 * full, plus only YOUR SHARE of the shared ones. Money you fronted for other
 * people is not your spending, it is a loan.
 */
export interface MonthlySummary {
  month: string;
  currency: string;
  personalMinor: number;
  /** Your share of shared expenses. */
  sharedShareMinor: number;
  /** personalMinor + sharedShareMinor — the number the app shows as "spent". */
  spentMinor: number;
  /** What you paid out at the till, including other people's shares. */
  paidOutMinor: number;
  /** Still owed to you from this month's expenses. */
  owedToYouMinor: number;
  owedByYouMinor: number;
  expenseCount: number;
  personalCount: number;
  sharedCount: number;
  byCategory: Array<{
    slug: string;
    name: string;
    color: string;
    spentMinor: number;
    shareBp: number;
    count: number;
  }>;
  /** Same month last period, so the UI can say "₹1,200 more than August". */
  previousSpentMinor?: number;
}
