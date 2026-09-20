package com.snaptab.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.snaptab.app.data.repository.DeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Server-sent pushes: someone added you to a tab, paid you back, or nudged you.
 *
 * Only runs when a `google-services.json` is present — the build works without one, and
 * the card-alert notifications that matter most are posted locally by SmsReceiver and
 * need no Firebase project at all.
 *
 * Messages are data-only so the app builds the notification itself and can attach the
 * action buttons; a `notification` payload would be drawn by the system with none.
 */
@AndroidEntryPoint
class SnapTabMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notifier: AlertNotifier
    @Inject lateinit var devices: DeviceRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Register straight away: a token that rotates while the app is closed would
        // otherwise leave the user silently unreachable until they next opened it.
        scope.launch { runCatching { devices.registerPushToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: return
        val body = data["body"].orEmpty()
        val kind = data["kind"]

        notifier.notifyServerMessage(
            title = title,
            body = body,
            deepLink = routeFor(kind),
            tag = (data["notificationId"] ?: title).hashCode()
        )
    }

    private fun routeFor(kind: String?): String? = when (kind) {
        "ALERT_NEEDS_EXPENSE" -> "inbox"
        "SCAN_READY", "SCAN_FAILED" -> "home"
        "SHARE_ASSIGNED", "ADDED_TO_GROUP" -> "groups"
        "SETTLEMENT_RECEIVED", "REMINDER_TO_PAY" -> "settle"
        else -> null
    }
}
