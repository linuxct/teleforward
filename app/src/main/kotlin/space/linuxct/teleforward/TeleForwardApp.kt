package space.linuxct.teleforward

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. `@HiltAndroidApp` bootstraps the DI graph, and [Configuration.Provider]
 * supplies WorkManager with the Hilt [HiltWorkerFactory] so `@HiltWorker`s (DeliveryWorker) get
 * their dependencies injected. The default WorkManager initializer is removed in the manifest so
 * this on-demand configuration is used.
 */
@HiltAndroidApp
class TeleForwardApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
