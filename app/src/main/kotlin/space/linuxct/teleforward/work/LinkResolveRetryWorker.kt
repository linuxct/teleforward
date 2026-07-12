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
import org.json.JSONObject
import space.linuxct.teleforward.data.db.dao.PendingLinkResolutionDao
import space.linuxct.teleforward.data.db.entity.PendingLinkResolutionEntity
import space.linuxct.teleforward.data.link.LinkResolver
import space.linuxct.teleforward.data.link.MagicLinkResult
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TelegramSender
import space.linuxct.teleforward.diag.DiagStore
import space.linuxct.teleforward.diag.ForensicRecord
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * The background half of the magic-link feature: an edit-after-send fallback. When
 * [DeliveryWorker]'s first (synchronous, ~8s-bounded) resolve attempt missed, the item was still
 * forwarded immediately WITHOUT a link and a [PendingLinkResolutionEntity] persisted. This worker
 * re-resolves the video from the persisted (channelId, title) — a fresh cache-busted fetch — and,
 * on a confident match, EDITs the already-sent Telegram message to append a `Link:` line.
 *
 * Entirely best-effort and isolated: it only ever touches its own pending queue and Telegram
 * *edits*, so it can never affect or delay the original forward. `doWork`:
 * 1. drops expired rows, loads the due batch, and re-resolves + edits (or reschedules) each;
 * 2. reschedules itself (a OneTimeWork, since periodic work is min-15-min) while any rows remain;
 * 3. wraps everything so a failure just retries later rather than crashing.
 *
 * `@HiltWorker` — instantiated by the HiltWorkerFactory supplied by TeleForwardApp.
 */
@HiltWorker
class LinkResolveRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pendingDao: PendingLinkResolutionDao,
    private val linkResolver: LinkResolver,
    private val telegramSender: TelegramSender,
    private val workManager: WorkManager,
    private val diagStore: DiagStore,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            pendingDao.deleteExpired(System.currentTimeMillis())
            val due = pendingDao.getDue(System.currentTimeMillis(), BATCH_LIMIT)
            for (row in due) {
                // Per-row isolation: one bad row must not abort the drain of the rest.
                runCatching { processRow(row) }
            }

            // Reschedule ourselves while any rows remain (periodic work is min-15-min, so use a
            // delayed OneTimeWork aimed at the earliest outstanding attempt).
            if (pendingDao.count() > 0) {
                val earliest = pendingDao.getEarliestNextAttempt()
                val delay = if (earliest != null) {
                    (earliest - System.currentTimeMillis()).coerceAtLeast(MIN_RESCHEDULE_MS)
                } else {
                    MIN_RESCHEDULE_MS
                }
                schedule(workManager, delay)
            }
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Never crash the feature: retry the whole pass later.
            Result.retry()
        }
    }

    /**
     * Re-resolve a single pending row and act on it: on a matched url, edit the sent message to append
     * the link (deleting the row on a done/terminal edit, leaving it on a transient edit failure);
     * on a still-missing match, either give up (attempts/window exhausted) or reschedule with backoff.
     * Best-effort diagnostics are logged for the whole outcome.
     */
    private suspend fun processRow(row: PendingLinkResolutionEntity) {
        val now = System.currentTimeMillis()
        val result = linkResolver.resolveChannelVideo(row.channelId, row.videoTitle)
        val url = result.url

        var editResult: SendResult? = null
        val rowAction: String

        if (url != null) {
            val edit = telegramSender.editAppendLink(
                chatId = row.chatId,
                messageId = row.messageId,
                isCaption = row.isCaption,
                currentText = row.sentText,
                url = url,
            )
            editResult = edit
            rowAction = when (edit) {
                // Applied (or already applied / gone / unusable): nothing more to do for this row.
                is SendResult.Success,
                is SendResult.Terminal,
                is SendResult.BadRequest,
                -> {
                    pendingDao.deleteById(row.id)
                    ACTION_DELETED
                }

                // The link resolved but the edit couldn't be delivered right now — leave the row and
                // bump its next attempt (without spending a resolve attempt).
                is SendResult.RetryAfter -> {
                    val delay = (edit.seconds * MILLIS_PER_SECOND).coerceAtLeast(MIN_RESCHEDULE_MS)
                    pendingDao.updateSchedule(row.id, row.attemptCount, now + delay)
                    ACTION_RESCHEDULED
                }

                is SendResult.Transient -> {
                    pendingDao.updateSchedule(
                        row.id,
                        row.attemptCount,
                        now + backoffMillis(row.attemptCount + 1),
                    )
                    ACTION_RESCHEDULED
                }
            }
        } else {
            val decision = decideRetry(row.attemptCount, now, row.expiresAt)
            rowAction = if (decision.giveUp) {
                pendingDao.deleteById(row.id)
                ACTION_GAVE_UP
            } else {
                pendingDao.updateSchedule(row.id, row.attemptCount + 1, now + decision.nextDelayMs)
                ACTION_RESCHEDULED
            }
        }

        logRetryTrace(row, result, editResult, rowAction)
    }

    /**
     * Append a `magicLinkTrace` diagnostics record for this retry attempt, mirroring
     * [DeliveryWorker]'s first-attempt record but tagged `"phase":"retry"` and carrying the retry
     * bookkeeping (`attemptCount`, `messageId`) plus the `editResult` / `rowAction`. Gated on
     * diagnostics being enabled; entirely best-effort (wrapped so it can NEVER affect the retry/edit
     * flow) and public-API/release-safe.
     */
    private suspend fun logRetryTrace(
        row: PendingLinkResolutionEntity,
        result: MagicLinkResult,
        editResult: SendResult?,
        rowAction: String,
    ) {
        runCatching {
            if (!settings.diagnosticsEnabled.first()) return
            val t = result.trace
            val json = JSONObject().apply {
                put("kind", "magicLinkTrace")
                put("phase", "retry")
                put("capturedAt", System.currentTimeMillis())
                put("channelId", row.channelId)
                put("videoTitle", row.videoTitle)
                put("attemptCount", row.attemptCount)
                put("messageId", row.messageId)
                put("outcome", t.outcome.name)
                putOpt("feedEntryCount", t.feedEntryCount)
                putOpt("feedNewestPublished", t.feedNewestPublished)
                putOpt("feedOldestPublished", t.feedOldestPublished)
                putOpt("feedNewestTitle", t.feedNewestTitle)
                putOpt("httpStatus", t.httpStatus)
                putOpt("error", t.error)
                putOpt("videoId", t.videoId)
                putOpt("url", t.url)
                putOpt("editResult", editResult?.let { editResultLabel(it) })
                put("rowAction", rowAction)
            }
            diagStore.append(ForensicRecord(json.toString()))
        }
    }

    /** Compact, release-safe label for the edit outcome recorded in diagnostics. */
    private fun editResultLabel(result: SendResult): String = when (result) {
        is SendResult.Success -> "Success"
        is SendResult.RetryAfter -> "RetryAfter(${result.seconds}s)"
        is SendResult.Transient -> "Transient"
        is SendResult.BadRequest -> "BadRequest"
        is SendResult.Terminal -> "Terminal"
    }

    companion object {
        const val UNIQUE_WORK_NAME = "magic_link_retry"

        /** First background retry fires ~45s after the missed first attempt. */
        const val FIRST_DELAY_MS = 45_000L

        /** Whole-effort window: a row older than this is abandoned regardless of attempts (~30 min). */
        const val MAX_WINDOW_MS = 30L * 60L * 1000L

        /** Max resolve attempts before giving up on a row. */
        const val MAX_ATTEMPTS = 5

        private const val MILLIS_PER_SECOND = 1000L

        /** Floor for any self-reschedule delay (WorkManager churn guard). */
        private const val MIN_RESCHEDULE_MS = 30_000L

        /** Max due rows processed per worker run. */
        private const val BATCH_LIMIT = 50

        private const val ACTION_DELETED = "deleted"
        private const val ACTION_RESCHEDULED = "rescheduled"
        private const val ACTION_GAVE_UP = "gaveUp"

        /**
         * Backoff between resolve attempts, indexed by the upcoming attempt number (1-based): roughly
         * +2m, +5m, +10m, +20m. Attempt 0's delay is [FIRST_DELAY_MS], applied at insert time.
         */
        private val BACKOFF_MS = longArrayOf(
            FIRST_DELAY_MS, // index 0 — unused at runtime (first delay set on insert)
            2L * 60L * 1000L, // attempt 1
            5L * 60L * 1000L, // attempt 2
            10L * 60L * 1000L, // attempt 3
            20L * 60L * 1000L, // attempt 4
        )

        /** Delay before the [attempt]-th resolve retry (clamped into the [BACKOFF_MS] table). */
        fun backoffMillis(attempt: Int): Long =
            BACKOFF_MS[attempt.coerceIn(1, BACKOFF_MS.size - 1)]

        /**
         * Pure give-up/backoff decision for a row whose re-resolve still found no match: give up when
         * the next attempt would reach [MAX_ATTEMPTS] or the row has passed [expiresAtMs]; otherwise
         * schedule the next attempt after [backoffMillis]. Extracted for unit testing.
         */
        fun decideRetry(attemptCount: Int, nowMs: Long, expiresAtMs: Long): RetryDecision {
            val nextAttempt = attemptCount + 1
            return if (nextAttempt >= MAX_ATTEMPTS || nowMs > expiresAtMs) {
                RetryDecision(giveUp = true, nextDelayMs = 0L)
            } else {
                RetryDecision(giveUp = false, nextDelayMs = backoffMillis(nextAttempt))
            }
        }

        /**
         * Enqueue (REPLACE) the unique, network-gated retry worker after [initialDelayMs]. Called both
         * to kick off the retry from [DeliveryWorker] and for the worker's own self-reschedule.
         */
        fun schedule(workManager: WorkManager, initialDelayMs: Long) {
            val request = OneTimeWorkRequestBuilder<LinkResolveRetryWorker>()
                .setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Result of [LinkResolveRetryWorker.decideRetry]: whether to abandon the row, else the next delay. */
data class RetryDecision(val giveUp: Boolean, val nextDelayMs: Long)
