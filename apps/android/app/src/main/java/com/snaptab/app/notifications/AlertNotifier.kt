package com.snaptab.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.snaptab.app.MainActivity
import com.snaptab.app.R
import com.snaptab.app.core.Money
import com.snaptab.app.data.sms.ParsedAlert
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notification the user actually sees when SnapTab spots a debit while the
 * app is closed.
 *
 * Three actions, because a debit is one of three things and only the user knows which:
 * log it as a personal expense (one tap, done), scan the bill because it is worth
 * splitting, or ignore it. "Log it" and "Ignore" resolve without opening the app at
 * all — they go to AlertActionReceiver.
 */
@Singleton
class AlertNotifier @Inject constructor(private val context: Context) {

    fun notifyDebit(alert: ParsedAlert, suggestedCategoryName: String?) {
        if (!canPost()) return
        NotificationChannels.ensure(context)

        val amount = Money.formatCompact(alert.amountMinor, alert.currency)
        val merchant = alert.merchantRaw ?: context.getString(R.string.error_unknown)
        val id = alert.fingerprint.hashCode()

        val body = if (suggestedCategoryName != null) {
            context.getString(R.string.notification_alert_body_with_category, suggestedCategoryName)
        } else {
            context.getString(R.string.notification_alert_body)
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_alert_title, amount, merchant))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            // Opens the sheet that offers "personal" or one of the user's existing groups.
            .setContentIntent(openInbox(alert.fingerprint, id))
            .addAction(
                0,
                context.getString(R.string.action_log_it),
                AlertActionReceiver.logPersonalIntent(context, alert, id)
            )
            .addAction(
                0,
                context.getString(R.string.action_scan_bill),
                openScan(alert.fingerprint, id)
            )
            .addAction(
                0,
                context.getString(R.string.action_ignore),
                AlertActionReceiver.ignoreIntent(context, alert.fingerprint, id)
            )
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Replaces the prompt with a short confirmation once the expense is logged. */
    fun notifyLogged(notificationId: Int, amount: String, categoryName: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_logged, amount, categoryName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(openHome(notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun notifyLogFailed(notificationId: Int) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_log_failed))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openHome(notificationId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /** A push relayed from the server: a new tab, a settlement, a nudge. */
    fun notifyServerMessage(title: String, body: String, deepLink: String?, tag: Int) {
        if (!canPost()) return
        NotificationChannels.ensure(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(deepLink?.let { openRoute(it, tag) } ?: openHome(tag))
            .build()
        NotificationManagerCompat.from(context).notify(tag, notification)
    }

    private fun openInbox(fingerprint: String, requestCode: Int): PendingIntent =
        openRoute("${MainActivity.ROUTE_LOG_ALERT}?fingerprint=$fingerprint", requestCode)

    private fun openScan(fingerprint: String, requestCode: Int): PendingIntent =
        openRoute("${MainActivity.ROUTE_SCAN}?fingerprint=$fingerprint", requestCode + 1)

    private fun openHome(requestCode: Int): PendingIntent = openRoute(MainActivity.ROUTE_HOME, requestCode)

    private fun openRoute(route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission from API 33. Without it, posting is a
     * silent no-op rather than a crash, and the alert still reaches the in-app inbox.
     */
    private fun canPost(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
