package com.snaptab.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.snaptab.app.R

/**
 * Three channels, so a user who finds the card-alert prompts too chatty can silence
 * those without losing "someone paid you back".
 */
object NotificationChannels {

    const val ALERTS = "snaptab_alerts"
    const val SCANS = "snaptab_scans"
    const val GENERAL = "snaptab_general"

    fun ensure(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                ALERTS,
                context.getString(R.string.channel_alerts_name),
                // High, because it is actionable and time-sensitive: the user is standing at
                // the counter and can log it in one tap.
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_alerts_description)
                enableVibration(true)
                setShowBadge(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                SCANS,
                context.getString(R.string.channel_scans_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_scans_description)
                setShowBadge(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                GENERAL,
                context.getString(R.string.channel_general_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_general_description)
                setShowBadge(true)
            }
        )
    }
}
