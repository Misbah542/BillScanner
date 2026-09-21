import type { Prisma, PrismaClient } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import type { NormalizedContact } from '../lib/contact.js';
import { normalizeContact, placeholderName } from '../lib/contact.js';
import { ApiError } from '../lib/errors.js';

type Db = PrismaClient | Prisma.TransactionClient;

export const publicUserFields = {
  id: true,
  name: true,
  email: true,
  phone: true,
  avatarUrl: true,
  status: true
} satisfies Prisma.UserSelect;

export type PublicUser = Prisma.UserGetPayload<{ select: typeof publicUserFields }>;

export async function findByContact(
  contact: NormalizedContact,
  db: Db = prisma
): Promise<PublicUser | null> {
  return db.user.findFirst({
    where: contact.kind === 'EMAIL' ? { email: contact.value } : { phone: contact.value },
    select: publicUserFields
  });
}

/**
 * Adding someone to a split by email or phone must work whether or not they have
 * ever heard of SnapTab. If there is no account, a placeholder `INVITED` user is
 * created: it holds a real balance in the ledger, and the first time that person
 * signs in with the same email or phone they take it over, debts and all.
 */
export async function findOrCreateByContact(
  raw: string,
  displayName: string | undefined,
  db: Db = prisma
): Promise<{ user: PublicUser; created: boolean; contact: NormalizedContact }> {
  const contact = normalizeContact(raw);

  const existing = await findByContact(contact, db);
  if (existing) return { user: existing, created: false, contact };

  const user = await db.user.create({
    data: {
      ...(contact.kind === 'EMAIL' ? { email: contact.value } : { phone: contact.value }),
      name: displayName?.trim() || placeholderName(contact),
      status: 'INVITED'
    },
    select: publicUserFields
  });
  return { user, created: true, contact };
}

/**
 * Turns the mixed bag the client sends — some known user ids, some bare emails or
 * phone numbers — into concrete user ids, creating placeholders where needed.
 */
export async function resolveParticipants(
  participants: ReadonlyArray<{
    userId?: string;
    email?: string;
    phone?: string;
    displayName?: string;
    value?: number;
  }>,
  db: Db = prisma
): Promise<Array<{ userId: string; value?: number; createdPlaceholder: boolean }>> {
  const resolved: Array<{ userId: string; value?: number; createdPlaceholder: boolean }> = [];

  for (const participant of participants) {
    if (participant.userId) {
      const exists = await db.user.findUnique({ where: { id: participant.userId }, select: { id: true } });
      if (!exists) throw ApiError.badRequest('UNKNOWN_USER', `No such person: ${participant.userId}.`);
      resolved.push({
        userId: participant.userId,
        ...(participant.value === undefined ? {} : { value: participant.value }),
        createdPlaceholder: false
      });
      continue;
    }

    const contact = participant.email ?? participant.phone;
    if (!contact) {
      throw ApiError.badRequest(
        'PARTICIPANT_UNIDENTIFIED',
        'Each person needs a user id, an email address or a phone number.'
      );
    }

    const { user, created } = await findOrCreateByContact(contact, participant.displayName, db);
    resolved.push({
      userId: user.id,
      ...(participant.value === undefined ? {} : { value: participant.value }),
      createdPlaceholder: created
    });
  }

  const seen = new Set<string>();
  for (const entry of resolved) {
    if (seen.has(entry.userId)) {
      throw ApiError.badRequest(
        'DUPLICATE_PARTICIPANT',
        'The same person appears twice in that split.'
      );
    }
    seen.add(entry.userId);
  }

  return resolved;
}
