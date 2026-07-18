package space.linuxct.teleforward.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.TelegramPoller
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Burst" half of the hybrid inbound strategy: after a message carrying action buttons is forwarded,
 * poll Telegram for a few minutes — the window in which the user realistically presses one — then
 * stop. This keeps remote actions responsive without a permanent foreground service; the opt-in
 * always-on service covers the rest.
 *
 * Runs a sequence of long polls rather than a `PeriodicWorkRequest` (whose 15-minute floor would make
 * a button press feel broken) and stays well inside WorkManager's ~10-minute execution budget.
 *
 * `@HiltWorker` — instantiated by the HiltWorkerFactory supplied by TeleForwardApp.
 */
@HiltWorker
class TelegramPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val poller: TelegramPoller,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!settings.remoteActionsEnabled.first()) return@withContext Result.success()

            val deadline = System.currentTimeMillis() + BURST_WINDOW_MS
            while (System.currentTimeMillis() < deadline && !isStopped) {
                // A failed cycle (no pairing, network error, or another getUpdates consumer) ends the
                // burst; the next forward will start a fresh one.
                if (!poller.pollOnce()) break
            }
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Inbound actions are a convenience: never churn WorkManager retries over them.
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "telegram_poll_burst"

        /** How long a burst keeps listening after a forward. Well under WorkManager's ~10 min cap. */
        private const val BURST_WINDOW_MS = 3L * 60L * 1000L

        /**
         * Start a burst if one isn't already running. `KEEP` (not `REPLACE`) so a flurry of forwards
         * extends coverage rather than continually restarting the listener.
         */
        fun scheduleBurst(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<TelegramPollWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(0L, TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
