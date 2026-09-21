import { env } from '../env.js';
import { logger } from '../lib/logger.js';
import { prisma } from '../lib/prisma.js';

/**
 * Sends one notification to every device the user has a push token for.
 *
 * This is the path that makes "detect a debit while the app is closed" actually
 * reach the user: the device posts the alert, the server files it, and this pushes
 * back a notification carrying the alert id, so its action buttons can log the
 * expense in one tap.
 *
 * With no FCM key configured the notification stays in the database and is shown
 * the next time the app opens — no pretending a push went out.
 */
export async function deliverPush(notificationId: string): Promise<void> {
  const notification = await prisma.notification.findUnique({
    where: { id: notificationId },
    include: {
      user: {
        select: {
          devices: {
            where: { pushToken: { not: null } },
            select: { id: true, pushToken: true, platform: true }
          }
        }
      }
    }
  });
  if (!notification) return;
  if (notification.pushedAt) return;

  const devices = notification.user.devices;
  if (devices.length === 0) {
    logger.debug({ notificationId }, 'no devices registered; notification stays in-app');
    return;
  }

  if (!env.FCM_SERVER_KEY) {
    logger.info(
      { notificationId, kind: notification.kind, devices: devices.length },
      'no FCM key configured; notification stays in-app'
    );
    return;
  }

  const failures: string[] = [];

  for (const device of devices) {
    try {
      const response = await fetch('https://fcm.googleapis.com/fcm/send', {
        method: 'POST',
        headers: {
          authorization: `key=${env.FCM_SERVER_KEY}`,
          'content-type': 'application/json'
        },
        body: JSON.stringify({
          to: device.pushToken,
          // A data-only message, so the app builds the notification itself and can
          // attach the action buttons. A `notification` block would be drawn by the
          // system with no actions.
          data: {
            notificationId: notification.id,
            kind: notification.kind,
            title: notification.title,
            body: notification.body,
            payload: JSON.stringify(notification.data ?? {})
          },
          priority: notification.kind === 'ALERT_NEEDS_EXPENSE' ? 'high' : 'normal',
          android: { priority: 'high' }
        })
      });

      if (!response.ok) {
        failures.push(`${device.id}:${response.status}`);
        continue;
      }

      const result = (await response.json()) as {
        failure?: number;
        results?: Array<{ error?: string }>;
      };
      const error = result.results?.[0]?.error;
      if (error === 'NotRegistered' || error === 'InvalidRegistration') {
        // The app was uninstalled. Drop the token so we stop trying.
        await prisma.device.update({ where: { id: device.id }, data: { pushToken: null } });
        logger.info({ deviceId: device.id }, 'dropped a dead push token');
      } else if (error) {
        failures.push(`${device.id}:${error}`);
      }
    } catch (error) {
      failures.push(`${device.id}:${(error as Error).message}`);
    }
  }

  await prisma.notification.update({
    where: { id: notificationId },
    data: { pushedAt: new Date() }
  });

  if (failures.length === devices.length) {
    // Every device failed: let the outbox retry rather than marking this done.
    throw new Error(`push failed for every device: ${failures.join(', ')}`);
  }
  if (failures.length > 0) {
    logger.warn({ notificationId, failures }, 'push failed for some devices');
  }
}
