/**
 * Seeds the built-in category taxonomy from packages/shared/data/categories.json,
 * so the database, the API's classifier and the Android app's chips can never drift
 * apart. Idempotent — safe to run on every deploy.
 *
 * With `SEED_DEMO=1` it also creates a small demo account: a restaurant bill split
 * four ways, a flat-share group, a solo Swiggy debit as a personal expense, and an
 * unmatched card alert sitting in the inbox.
 */
import { PrismaClient } from '@prisma/client';
import { CATEGORIES, normalizeMerchant, computeSplit } from '@snaptab/shared';

const prisma = new PrismaClient();

async function seedCategories(): Promise<void> {
  for (const [index, category] of CATEGORIES.entries()) {
    await prisma.category.upsert({
      where: { ownerId_slug: { ownerId: null as never, slug: category.slug } },
      create: {
        slug: category.slug,
        name: category.name,
        iconKey: category.icon,
        colorHex: category.color,
        tintHex: category.tintLight,
        kind: category.kind,
        sortOrder: index
      },
      update: {
        name: category.name,
        iconKey: category.icon,
        colorHex: category.color,
        tintHex: category.tintLight,
        kind: category.kind,
        sortOrder: index
      }
    });
  }
  console.log(`seeded ${CATEGORIES.length} categories`);
}

async function seedDemo(): Promise<void> {
  const restaurant = await prisma.category.findFirstOrThrow({
    where: { slug: 'restaurant', ownerId: null }
  });
  const groceries = await prisma.category.findFirstOrThrow({
    where: { slug: 'groceries', ownerId: null }
  });

  const me = await prisma.user.upsert({
    where: { email: 'demo@snaptab.app' },
    create: {
      email: 'demo@snaptab.app',
      emailVerified: true,
      name: 'Demo User',
      status: 'ACTIVE',
      identities: { create: { provider: 'EMAIL_OTP', providerSubject: 'demo@snaptab.app' } }
    },
    update: {},
    select: { id: true }
  });

  // Aditi has an account; Sneha was added by phone and has never signed in.
  const aditi = await prisma.user.upsert({
    where: { email: 'aditi@example.com' },
    create: { email: 'aditi@example.com', name: 'Aditi Deshpande', status: 'ACTIVE' },
    update: {},
    select: { id: true }
  });
  const rohan = await prisma.user.upsert({
    where: { email: 'rohan@example.com' },
    create: { email: 'rohan@example.com', name: 'Rohan Kamat', status: 'ACTIVE' },
    update: {},
    select: { id: true }
  });
  const sneha = await prisma.user.upsert({
    where: { phone: '+919930088214' },
    create: { phone: '+919930088214', name: 'Sneha N.', status: 'INVITED' },
    update: {},
    select: { id: true }
  });

  const group = await prisma.group.upsert({
    where: { id: '11111111-1111-4111-8111-111111111111' },
    create: {
      id: '11111111-1111-4111-8111-111111111111',
      name: 'Flat 402',
      iconKey: 'home',
      createdById: me.id,
      members: {
        create: [
          { userId: me.id, role: 'OWNER' },
          { userId: aditi.id },
          { userId: rohan.id },
          { userId: sneha.id }
        ]
      }
    },
    update: {},
    select: { id: true }
  });

  const already = await prisma.expense.count({ where: { createdById: me.id } });
  if (already > 0) {
    console.log('demo data already present');
    return;
  }

  // 1. A shared restaurant bill, split four ways equally.
  const split = computeSplit({
    method: 'EQUAL',
    totalMinor: 257_500,
    participants: [me, aditi, rohan, sneha].map((user) => ({ userId: user.id })),
    payerUserId: me.id
  });

  await prisma.expense.create({
    data: {
      kind: 'SHARED',
      source: 'SCAN',
      groupId: group.id,
      createdById: me.id,
      paidById: me.id,
      merchantName: 'Social Offline, Bandra',
      merchantNormalized: normalizeMerchant('Social Offline, Bandra'),
      occurredAt: new Date('2026-09-19T21:42:00+05:30'),
      categoryId: restaurant.id,
      categorySource: 'SUGGESTED',
      categoryConfidence: 0.94,
      itemTotalMinor: 228_500,
      serviceChargeMinor: 22_850,
      taxMinor: 6168,
      roundOffMinor: -18,
      totalMinor: 257_500,
      splitMethod: 'EQUAL',
      items: {
        create: [
          { position: 0, name: 'Aerated Beverages', quantityMilli: 2000, unitPriceMinor: 12_000, amountMinor: 24_000 },
          { position: 1, name: 'Chilli Cheese Toast', quantityMilli: 1000, unitPriceMinor: 34_500, amountMinor: 34_500 },
          { position: 2, name: 'LIIT Pitcher', quantityMilli: 2000, unitPriceMinor: 65_000, amountMinor: 130_000 },
          { position: 3, name: 'Butter Chicken', quantityMilli: 1000, unitPriceMinor: 40_000, amountMinor: 40_000 }
        ]
      },
      taxLines: {
        create: [
          { label: 'Service Charge', kind: 'SERVICE_CHARGE', rateBp: 1000, amountMinor: 22_850 },
          { label: 'CGST', kind: 'CGST', rateBp: 250, amountMinor: 3084 },
          { label: 'SGST', kind: 'SGST', rateBp: 250, amountMinor: 3084 }
        ]
      },
      shares: {
        create: split.shares.map((share) => ({
          userId: share.userId,
          amountMinor: share.amountMinor,
          paidMinor: share.userId === me.id ? share.amountMinor : 0,
          weightBp: share.weightBp,
          ...(share.userId === me.id ? { settledAt: new Date() } : {})
        }))
      }
    }
  });

  // 2. A personal expense with no bill at all — just a card debit. This is the
  //    "not everything has a bill" case the whole PERSONAL kind exists for.
  await prisma.expense.create({
    data: {
      kind: 'PERSONAL',
      source: 'ALERT',
      createdById: me.id,
      paidById: me.id,
      merchantName: 'SWIGGY',
      merchantNormalized: normalizeMerchant('SWIGGY'),
      occurredAt: new Date('2026-09-20T13:12:00+05:30'),
      categoryId: restaurant.id,
      categorySource: 'SUGGESTED',
      categoryConfidence: 0.88,
      itemTotalMinor: 48_600,
      totalMinor: 48_600
    }
  });

  // 3. Another personal one, entered by hand.
  await prisma.expense.create({
    data: {
      kind: 'PERSONAL',
      source: 'MANUAL',
      createdById: me.id,
      paidById: me.id,
      merchantName: 'Local kirana',
      merchantNormalized: normalizeMerchant('Local kirana'),
      occurredAt: new Date('2026-09-18T19:05:00+05:30'),
      categoryId: groceries.id,
      categorySource: 'USER',
      itemTotalMinor: 34_000,
      totalMinor: 34_000
    }
  });

  // 4. An unmatched debit sitting in the inbox waiting to be filed.
  await prisma.bankAlert.create({
    data: {
      userId: me.id,
      direction: 'DEBIT',
      channel: 'SMS',
      status: 'UNMATCHED',
      amountMinor: 1_249_900,
      merchantRaw: 'CROMA, PHOENIX',
      merchantNormalized: normalizeMerchant('CROMA, PHOENIX'),
      accountMask: '9082',
      accountKind: 'CREDIT_CARD',
      bankId: 'icici',
      bankName: 'ICICI Bank',
      referenceNumber: 'DEMO0000001',
      fingerprint: 'demo-croma-1',
      confidence: 0.9,
      occurredAt: new Date('2026-09-19T19:38:00+05:30')
    }
  });

  console.log('seeded demo account: demo@snaptab.app');
}

async function main(): Promise<void> {
  await seedCategories();
  if (process.env.SEED_DEMO === '1') await seedDemo();
}

main()
  .catch((error) => {
    console.error(error);
    process.exit(1);
  })
  .finally(() => void prisma.$disconnect());
