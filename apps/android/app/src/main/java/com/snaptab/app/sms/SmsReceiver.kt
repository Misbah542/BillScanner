package com.snaptab.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.snaptab.app.data.local.PendingAlertDao
import com.snaptab.app.data.local.PendingAlertEntity
import com.snaptab.app.data.local.TokenStore
import com.snaptab.app.data.sms.BankAlertParser
import com.snaptab.app.data.sms.ParsedAlert
import com.snaptab.app.notifications.AlertNotifier
import com.snaptab.app.work.AlertSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Catches incoming SMS and turns bank alerts into something the user can act on.
 *
 * This is the piece that makes "detect a debit while the app is closed" work: a
 * manifest-registered receiver is started by the system whether or not SnapTab is
 * running, so a swiped-away app still notices the charge.
 *
 * What happens here, in order:
 *   1. parse ON THE DEVICE with the shared rules — the message body never leaves the
 *      phone unless the user opted in;
 *   2. queue the handful of extracted fields in Room, keyed by fingerprint so a
 *      re-delivered message cannot double an expense;
 *   3. post a notification with Log it / Scan bill / Ignore;
 *   4. hand the queue to WorkManager, which uploads when there is a connection.
 *
 * Nothing here can throw into the system's SMS delivery: a receiver that crashes takes
 * the whole broadcast with it, so every step is wrapped.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var parser: BankAlertParser
    @Inject lateinit var pendingAlerts: PendingAlertDao
    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var tokenStore: TokenStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Messages longer than 160 characters arrive as several parts; join them before
        // parsing, or the amount and the merchant can land in different fragments.
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filterNotNull()
            ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().originatingAddress
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages.first().timestampMillis
            .takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.now()

        // Keep the broadcast alive across the coroutine: without this the process can be
        // killed mid-write on a device under memory pressure.
        val pendingResult = goAsync()

        scope.launch {
            try {
                handle(body, sender, receivedAt, context)
            } catch (error: Throwable) {
                Log.w(TAG, "could not handle an incoming message", error)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun handle(body: String, sender: String?, receivedAt: Instant, context: Context) {
        // Not signed in, or the user turned the feature off: do nothing at all. Parsing a
        // message we have no right to act on would be the wrong default.
        if (!tokenStore.isSmsEnabledNow()) return
        if (tokenStore.refreshToken().isNullOrBlank()) return

        val parsed = parser.parse(body, sender, receivedAt) ?: return

        val keepBody = tokenStore.shouldSendAlertBodies()
        val inserted = pendingAlerts.enqueue(parsed.toEntity(keepBody))
        // -1 means the fingerprint was already queued: the same message again. Say nothing.
        if (inserted == -1L) return

        if (parsed.direction == ParsedAlert.Direction.DEBIT) {
            runCatching { notifier.notifyDebit(parsed, suggestedCategoryName = null) }
                .onFailure { Log.w(TAG, "could not post the alert notification", it) }
        }

        AlertSyncWorker.enqueue(context)
    }

    private fun ParsedAlert.toEntity(keepBody: Boolean) = PendingAlertEntity(
        fingerprint = fingerprint,
        direction = direction.name,
        amountMinor = amountMinor,
        currency = currency,
        merchantRaw = merchantRaw,
        accountMask = accountMask,
        accountKind = accountKind.name,
        bankId = bankId,
        bankName = bankName,
        referenceNumber = referenceNumber,
        confidence = confidence,
        occurredAtEpoch = occurredAt.toEpochMilli(),
        rawBody = if (keepBody) rawBody else null,
        queuedAtEpoch = System.currentTimeMillis()
    )

    private companion object {
        const val TAG = "SnapTabSmsReceiver"

        /**
         * A receiver has no lifecycle to scope to, so this deliberately outlives the
         * onReceive call; goAsync() keeps the process up while it runs.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
