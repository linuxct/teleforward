package space.linuxct.teleforward.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.MessageBuilder
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TelegramSender
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drains the outbox oldest-first (plan "Reliable delivery"):
 *
 * 1. If forwarding is paused, do nothing.
 * 2. Recover rows stranded in SENDING by a previous crash, then pull the deliverable batch.
 * 3. Without a paired recipient, fail the batch with an actionable message.
 * 4. For each row: expire it if it aged out or exhausted its attempts (deleting its image files),
 *    otherwise mark SENDING, send via [telegramSender], and map the [SendResult] to
 *    SENT / reschedule-with-backoff / FAILED. Image files are deleted on SENT and EXPIRED.
 *
 * Returns [Result.retry] when at least one row was rescheduled (transient / 429) so WorkManager
 * re-runs with the exponential backoff configured by the enqueuer; otherwise [Result.success].
 *
 * `@HiltWorker` — instantiated by the HiltWorkerFactory supplied by TeleForwardApp.
 */
@HiltWorker
class DeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outboxRepository: OutboxRepository,
    private val telegramSender: TelegramSender,
    private val messageBuilder: MessageBuilder,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val snapshot = settings.snapshot()

        // Paused globally: don't drain while forwarding is off.
        if (!snapshot.forwardingEnabled) {
            return@withContext Result.success()
        }

        // Recover rows a previous run left mid-flight before selecting the batch.
        outboxRepository.resetStaleSending()

        val batch = outboxRepository.getDeliverable(BATCH_LIMIT)
        if (batch.isEmpty()) {
            return@withContext Result.success()
        }

        val chatId = snapshot.chatId
        if (chatId == null) {
            // Nothing paired yet: fail deliverable rows with an actionable log entry rather than
            // spinning forever against a missing recipient.
            batch.forEach { outboxRepository.markFailed(it.outbox.id, NO_RECIPIENT_ERROR) }
            return@withContext Result.success()
        }

        val now = System.currentTimeMillis()
        val expiryWindowMs = snapshot.outboxExpiryHours.toLong() * MILLIS_PER_HOUR
        var needsRetry = false

        for (item in batch) {
            val row = item.outbox
            val id = row.id

            // Expiry / exhausted-attempts guard, evaluated before we spend a send attempt.
            val agedOut = expiryWindowMs > 0L && (now - row.createdAt) > expiryWindowMs
            if (agedOut || row.attemptCount >= MAX_ATTEMPTS) {
                outboxRepository.markExpired(id)
                deleteImageFiles(item)
                continue
            }

            try {
                outboxRepository.markSending(id)
                when (val result = telegramSender.send(item, chatId)) {
                    is SendResult.Success -> {
                        outboxRepository.markSent(id)
                        deleteImageFiles(item)
                    }

                    is SendResult.RetryAfter -> {
                        val nextAttemptAt = now + result.seconds * MILLIS_PER_SECOND
                        outboxRepository.reschedule(
                            id = id,
                            attemptCount = row.attemptCount + 1,
                            nextAttemptAt = nextAttemptAt,
                            error = "429 retry_after=${result.seconds}s",
                        )
                        needsRetry = true
                    }

                    is SendResult.Transient -> {
                        outboxRepository.reschedule(
                            id = id,
                            attemptCount = row.attemptCount + 1,
                            nextAttemptAt = now + backoffMillis(row.attemptCount),
                            error = result.message,
                        )
                        needsRetry = true
                    }

                    is SendResult.BadRequest -> outboxRepository.markFailed(id, result.message)

                    is SendResult.Terminal -> outboxRepository.markFailed(id, result.message)
                }
            } catch (ce: CancellationException) {
                // Worker stopped/cancelled: propagate so WorkManager reschedules cleanly. The row
                // stays SENDING and is recovered by resetStaleSending() on the next run.
                throw ce
            } catch (t: Throwable) {
                // Unexpected failure while sending: treat as transient so one bad row doesn't abort
                // the whole drain, and retry it with backoff.
                outboxRepository.reschedule(
                    id = id,
                    attemptCount = row.attemptCount + 1,
                    nextAttemptAt = now + backoffMillis(row.attemptCount),
                    error = t.message ?: t.javaClass.simpleName,
                )
                needsRetry = true
            }
        }

        if (needsRetry) Result.retry() else Result.success()
    }

    /** Best-effort deletion of an item's cached image files; missing/undeletable files are ignored. */
    private fun deleteImageFiles(item: OutboxWithImages) {
        item.images.forEach { image ->
            runCatching { File(image.filePath).delete() }
        }
    }

    /** Exponential backoff: [MIN_BACKOFF_SECONDS] * 2^attempt, capped at [MAX_BACKOFF_SECONDS]. */
    private fun backoffMillis(attemptCount: Int): Long {
        val shift = attemptCount.coerceIn(0, MAX_BACKOFF_SHIFT)
        val seconds = (MIN_BACKOFF_SECONDS shl shift).coerceAtMost(MAX_BACKOFF_SECONDS)
        return seconds * MILLIS_PER_SECOND
    }

    companion object {
        const val UNIQUE_WORK_NAME = "outbox_drain"
        const val MAX_ATTEMPTS = 8
        const val MIN_BACKOFF_SECONDS = 30L

        /** Max deliverable rows drained per worker run. */
        private const val BATCH_LIMIT = 50

        /** Upper bound on computed backoff (1 hour). */
        private const val MAX_BACKOFF_SECONDS = 3600L

        /** Cap on the backoff shift to keep `MIN_BACKOFF_SECONDS shl shift` well within Long range. */
        private const val MAX_BACKOFF_SHIFT = 16

        private const val MILLIS_PER_SECOND = 1000L
        private const val MILLIS_PER_HOUR = 60L * 60L * 1000L

        private const val NO_RECIPIENT_ERROR = "no recipient paired"
    }
}
