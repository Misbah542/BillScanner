import type { NotificationKind, Prisma } from '@prisma/client';
import { prisma } from '../lib/prisma.js';

type Db = Prisma.TransactionClient | typeof prisma;

export interface NotificationDraft {
  userId: string;
  kind: NotificationKind;
  title: string;
  body: string;
  data?: Prisma.InputJsonValue;
}

/**
 * Writes the notification and an outbox row in the SAME transaction as whatever
 * caused it, then lets a worker deliver the push. A slow or failing FCM call can
 * never fail the request that created someone's expense, and a committed expense
 * can never lose its notification.
 */
export async function enqueueNotification(db: Db, draft: NotificationDraft): Promise<void> {
  const notification = await db.notification.create({
    data: {
      userId: draft.userId,
      kind: draft.kind,
      title: draft.title,
      body: draft.body,
      ...(draft.data === undefined ? {} : { data: draft.data })
    },
    select: { id: true }
  });

  await db.outboxEvent.create({
    data: {
      topic: 'notification.push',
      payload: { notificationId: notification.id, userId: draft.userId }
    }
  });
}

export async function markNotificationsRead(userId: string, ids: string[]): Promise<number> {
  const result = await prisma.notification.updateMany({
    where: { userId, ...(ids.length > 0 ? { id: { in: ids } } : {}), readAt: null },
    data: { readAt: new Date() }
  });
  return result.count;
}
