package com.snaptab.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.snaptab.app.core.StartupTask
import com.snaptab.app.notifications.NotificationChannels
import com.snaptab.app.work.RefreshWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is initialised here rather than by its default provider (removed in the
 * manifest) so Hilt can inject repositories into workers — AlertSyncWorker needs the
 * alert repository to drain what the SMS receiver queued.
 */
@HiltAndroidApp
class SnapTabApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** No-op in the live flavour; seeds a demo session in the demo one. */
    @Inject lateinit var startupTask: StartupTask

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before anything can post to them, and the SMS receiver can
        // fire before any Activity has ever been created.
        NotificationChannels.ensure(this)
        RefreshWorker.schedule(this)

        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch { runCatching { startupTask.run() } }
    }
}
