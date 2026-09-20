package com.snaptab.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.snaptab.app.core.ApiResult
import com.snaptab.app.core.Money
import com.snaptab.app.data.repository.AlertRepository
import com.snaptab.app.data.sms.ParsedAlert
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the notification's buttons without opening the app.
 *
 * "Log it" is the whole point of the personal-expense kind: a ₹486 Swiggy charge with
 * no bill and nobody to split with becomes a complete expense in one tap from the lock
 * screen. "Ignore" files it away so the inbox stops asking.
 */
@AndroidEntryPoint
class AlertActionReceiver : BroadcastReceiver() {

    @Inject lateinit var alerts: AlertRepository
    @Inject lateinit var notifier: AlertNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, fingerprint.hashCode())

        val pendingResult = goAsync()
        scope.launch {
            try {
                when (action) {
                    ACTION_LOG_PERSONAL -> logPersonal(intent, fingerprint, notificationId)
                    ACTION_IGNORE -> {
                        alerts.ignoreByFingerprint(fingerprint)
                        notifier.cancel(notificationId)
                    }
                    else -> Unit
                }
            } catch (error: Throwable) {
                Log.w(TAG, "notification action failed", error)
                runCatching { notifier.notifyLogFailed(notificationId) }
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun logPersonal(intent: Intent, fingerprint: String, notificationId: Int) {
        val amountMinor = intent.getLongExtra(EXTRA_AMOUNT_MINOR, 0L)
        val currency = intent.getStringExtra(EXTRA_CURRENCY) ?: "INR"

        when (val result = alerts.logPersonalByFingerprint(fingerprint)) {
            is ApiResult.Success -> notifier.notifyLogged(
                notificationId = notificationId,
                amount = Money.formatCompact(amountMinor, currency),
                categoryName = result.data.category?.name.orEmpty().ifBlank { "an expense" }
            )
            is ApiResult.Failure -> {
                Log.w(TAG, "could not log the alert: ${result.code} ${result.message}")
                notifier.notifyLogFailed(notificationId)
            }
        }
    }

    companion object {
        private const val TAG = "SnapTabAlertAction"
        private const val ACTION_LOG_PERSONAL = "com.snaptab.app.LOG_PERSONAL"
        private const val ACTION_IGNORE = "com.snaptab.app.IGNORE_ALERT"
        private const val EXTRA_FINGERPRINT = "fingerprint"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_AMOUNT_MINOR = "amount_minor"
        private const val EXTRA_CURRENCY = "currency"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun logPersonalIntent(context: Context, alert: ParsedAlert, notificationId: Int): PendingIntent {
            val intent = Intent(context, AlertActionReceiver::class.java).apply {
                action = ACTION_LOG_PERSONAL
                putExtra(EXTRA_FINGERPRINT, alert.fingerprint)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_AMOUNT_MINOR, alert.amountMinor)
                putExtra(EXTRA_CURRENCY, alert.currency)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId * 10 + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun ignoreIntent(context: Context, fingerprint: String, notificationId: Int): PendingIntent {
            val intent = Intent(context, AlertActionReceiver::class.java).apply {
                action = ACTION_IGNORE
                putExtra(EXTRA_FINGERPRINT, fingerprint)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId * 10 + 2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
