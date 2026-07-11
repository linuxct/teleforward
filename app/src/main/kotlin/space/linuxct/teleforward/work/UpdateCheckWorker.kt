package space.linuxct.teleforward.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.update.UpdateNotifier
import space.linuxct.teleforward.data.update.UpdateRepository
import space.linuxct.teleforward.data.update.UpdateResult

/**
 * Daily background update check (plan "GitHub update check"). Runs [UpdateRepository.check] and,
 * when a newer release is found, posts the update notification — but only **once per version**:
 * it compares the latest tag against [SettingsRepository.lastNotifiedUpdateVersion] so the same
 * version isn't re-announced on every daily run.
 *
 * Returns [Result.retry] (bounded) on a transient [UpdateResult.Failed]; otherwise
 * [Result.success]. `@HiltWorker` — instantiated by the HiltWorkerFactory from TeleForwardApp.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateRepository: UpdateRepository,
    private val updateNotifier: UpdateNotifier,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when (val result = updateRepository.check()) {
            is UpdateResult.Available -> {
                val lastNotified = settings.lastNotifiedUpdateVersion.first()
                if (result.latest != lastNotified) {
                    updateNotifier.notifyUpdate(result.latest, result.releaseUrl)
                    settings.setLastNotifiedUpdateVersion(result.latest)
                }
                Result.success()
            }

            is UpdateResult.UpToDate -> Result.success()

            // Transient failure (network, rate limit, …): retry a few times, then give up until the
            // next periodic run so we don't churn the WorkManager backoff indefinitely.
            is UpdateResult.Failed ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }

    companion object {
        const val UNIQUE_WORK_NAME = "update_check"
        private const val MAX_ATTEMPTS = 3
    }
}
