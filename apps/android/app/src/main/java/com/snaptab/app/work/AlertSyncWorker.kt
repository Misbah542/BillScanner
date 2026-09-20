package com.snaptab.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.snaptab.app.core.ApiResult
import com.snaptab.app.data.repository.AlertRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Drains the alerts SmsReceiver queued locally.
 *
 * The receiver deliberately does no networking: a message can arrive on the Tube with
 * no signal, and an upload from inside a broadcast would be killed anyway. So the
 * receiver writes to Room and hands off here, where WorkManager waits for a connection
 * and survives a reboot.
 */
@HiltWorker
class AlertSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: AlertRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (val result = alerts.syncPending()) {
        is ApiResult.Success -> Result.success()
        is ApiResult.Failure ->
            // Offline or a server blip: WorkManager will back off and try again. A 4xx
            // means the payload will never be accepted, so stop rather than loop.
            if (result.isOffline || (result.httpStatus ?: 500) >= 500) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "snaptab-alert-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AlertSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // APPEND_OR_REPLACE, not REPLACE: two messages arriving seconds apart must both
            // get uploaded, so the second enqueue queues behind the first instead of
            // cancelling it mid-flight.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
