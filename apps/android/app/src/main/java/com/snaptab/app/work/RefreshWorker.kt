package com.snaptab.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.snaptab.app.data.repository.CategoryRepository
import com.snaptab.app.data.repository.ExpenseRepository
import com.snaptab.app.data.repository.GroupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keeps the offline cache warm so opening the app is instant, and picks up the
 * category taxonomy when the server adds to it.
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val expenses: ExpenseRepository,
    private val groups: GroupRepository,
    private val categories: CategoryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Failures here are not worth retrying aggressively: the next period comes soon
        // and every screen refreshes on open anyway.
        runCatching { categories.refresh() }
        runCatching { expenses.refresh(kind = null) }
        runCatching { groups.refresh() }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "snaptab-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
