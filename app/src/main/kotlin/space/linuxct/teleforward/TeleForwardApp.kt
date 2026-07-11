package space.linuxct.teleforward

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import space.linuxct.teleforward.work.UpdateCheckWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application entry point. `@HiltAndroidApp` bootstraps the DI graph, and [Configuration.Provider]
 * supplies WorkManager with the Hilt [HiltWorkerFactory] so `@HiltWorker`s (DeliveryWorker,
 * UpdateCheckWorker) get their dependencies injected. The default WorkManager initializer is removed
 * in the manifest so this on-demand configuration is used.
 *
 * [onCreate] also enqueues the daily GitHub update check as unique periodic work.
 */
@HiltAndroidApp
class TeleForwardApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        scheduleUpdateCheck()
    }

    /**
     * Enqueue the once-a-day update check as unique periodic work with
     * [ExistingPeriodicWorkPolicy.KEEP] — safe to call every launch; it won't reset the existing
     * schedule. Gated on a connected network so it never runs offline.
     */
    private fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(UPDATE_CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UPDATE_CHECK_INTERVAL_HOURS = 24L
    }
}
