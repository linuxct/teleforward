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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.TelegramPoller
import space.linuxct.teleforward.service.TelegramListenerService
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
 * ## Why there is also a catch-up chain
 *
 * Buttons stay pressable for as long as the message exists, but the burst only listens for three
 * minutes. A press after that used to reach nobody: the client spun forever, and the app never even
 * learned a press had happened — the exact bug that prompted this. Telegram queues the update for
 * 24h, so the press is not lost, it merely needs someone to come back and collect it.
 *
 * So after the burst, this schedules a handful of decaying single-shot checks. They are cheap — one
 * short request each, no held connection — and they turn "silent forever" into "answered within
 * minutes, with an explanation of why the button appeared to hang".
 *
 * `@HiltWorker` — instantiated by the HiltWorkerFactory supplied by TeleForwardApp.
 */
@HiltWorker
class TelegramPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val poller: TelegramPoller,
    private val settings: SettingsRepository,
    private val workManager: WorkManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!settings.remoteActionsEnabled.first()) return@withContext Result.success()
            // The always-on service already holds a continuous poll. A second consumer would just
            // trade 409s with it, and Telegram would hand the same press to whichever won — so stand
            // down rather than fight the better-placed listener.
            //
            // Keyed on the service actually running, NOT on the "Always listening" setting. Those two
            // disagree in exactly the situation that matters: the setting survives a reboot but the
            // service does not, so trusting it would hand every press to a listener that isn't there.
            if (TelegramListenerService.isRunning) return@withContext Result.success()

            when (inputData.getInt(KEY_STAGE, STAGE_BURST)) {
                STAGE_BURST -> runBurst()
                else -> poller.pollOnce(timeoutSeconds = CATCHUP_POLL_SECONDS)
            }
            scheduleNextCatchUp(inputData.getInt(KEY_STAGE, STAGE_BURST))
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Inbound actions are a convenience: never churn WorkManager retries over them.
            Result.success()
        }
    }

    /**
     * Long-poll until the window closes.
     *
     * A failed cycle no longer ends the burst. It previously did, so a single DNS blip in the first
     * second silently discarded the whole three minutes of coverage — indistinguishable, from the
     * user's side, from the app ignoring them. Transient failures are now absorbed with a short
     * backoff, and only a sustained run of them gives up.
     */
    private suspend fun runBurst() {
        val deadline = System.currentTimeMillis() + BURST_WINDOW_MS
        var consecutiveFailures = 0
        while (System.currentTimeMillis() < deadline && !isStopped) {
            if (poller.pollOnce()) {
                consecutiveFailures = 0
                continue
            }
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
            delay(FAILURE_BACKOFF_MS)
        }
    }

    /** Queue the next decaying check, so a late press is still collected and explained. */
    private fun scheduleNextCatchUp(stage: Int) {
        val next = stage + 1
        val delayMs = CATCHUP_DELAYS_MS.getOrNull(next - 1) ?: return
        workManager.enqueueUniqueWork(
            UNIQUE_CATCHUP_NAME,
            ExistingWorkPolicy.REPLACE,
            request(stage = next, delayMs = delayMs),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "telegram_poll_burst"

        /** Separate chain so a fresh burst can restart the catch-ups without cancelling itself. */
        const val UNIQUE_CATCHUP_NAME = "telegram_poll_catchup"

        private const val KEY_STAGE = "stage"
        private const val STAGE_BURST = 0

        /** How long a burst keeps listening after a forward. Well under WorkManager's ~10 min cap. */
        private const val BURST_WINDOW_MS = 3L * 60L * 1000L

        /**
         * When to look again after the burst. Decaying, so a press a minute after the window closes is
         * answered quickly while an abandoned chat costs almost nothing: five short requests, spread
         * over roughly two hours, and only ever one chain at a time.
         */
        private val CATCHUP_DELAYS_MS = listOf(
            2L * 60L * 1000L,
            5L * 60L * 1000L,
            15L * 60L * 1000L,
            30L * 60L * 1000L,
            60L * 60L * 1000L,
        )

        /** A catch-up drains what is already queued; it must not hold a long poll open. */
        private const val CATCHUP_POLL_SECONDS = 0

        private const val FAILURE_BACKOFF_MS = 5_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /**
         * Start (or restart) a burst.
         *
         * `REPLACE`, not `KEEP`. The old comment claimed `KEEP` let a flurry of forwards "extend
         * coverage", but it does the opposite: while the first burst is RUNNING every later request is
         * dropped, so twenty notifications over ten minutes still bought a single three-minute window
         * measured from the first one. Replacing genuinely extends it, which is what was intended.
         */
        fun scheduleBurst(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request(stage = STAGE_BURST, delayMs = 0L),
            )
        }

        private fun request(stage: Int, delayMs: Long) =
            OneTimeWorkRequestBuilder<TelegramPollWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(KEY_STAGE to stage))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
    }
}
