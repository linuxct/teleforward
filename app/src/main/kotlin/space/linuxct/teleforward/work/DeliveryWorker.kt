package space.linuxct.teleforward.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import space.linuxct.teleforward.data.db.dao.PendingLinkResolutionDao
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import space.linuxct.teleforward.data.db.entity.PendingLinkResolutionEntity
import space.linuxct.teleforward.data.link.LinkResolver
import space.linuxct.teleforward.data.link.MagicLinkOutcome
import space.linuxct.teleforward.data.link.MagicLinkResult
import space.linuxct.teleforward.data.link.YouTube
import space.linuxct.teleforward.data.link.magicLinkKind
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.MediaForwardController
import space.linuxct.teleforward.data.telegram.MessageBuilder
import space.linuxct.teleforward.data.telegram.RemoteActionKeyboards
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TelegramSender
import space.linuxct.teleforward.diag.DiagStore
import space.linuxct.teleforward.diag.ForensicRecord
import space.linuxct.teleforward.diag.RemoteActionDiag
import space.linuxct.teleforward.service.NotificationActionGateway
import space.linuxct.teleforward.domain.NotificationActions
import space.linuxct.teleforward.domain.remoteButtons
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
    private val linkResolver: LinkResolver,
    private val diagStore: DiagStore,
    private val pendingLinkResolutionDao: PendingLinkResolutionDao,
    private val remoteActionKeyboards: RemoteActionKeyboards,
    private val remoteActionDiag: RemoteActionDiag,
    private val actionGateway: NotificationActionGateway,
    private val mediaForwardController: MediaForwardController,
    private val workManager: WorkManager,
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
                // Best-effort "magic link" reconstruction, fully isolated from the send outcome:
                // bounded by a 12s timeout and try/caught, so a failure/timeout only means no Link:
                // line — it never throws, retries, or fails the item.
                val resolution = withTimeoutOrNull(LINK_RESOLVE_TIMEOUT_MS) {
                    runCatching {
                        linkResolver.resolve(row, settings.magicLinkDisabledPackages.first())
                    }.getOrNull()
                }
                // Release-safe resolution trace: purely diagnostic, never affects delivery.
                resolution?.let { logMagicLinkTrace(row, it) }
                // Best-effort inline action buttons; a failure here must never block the forward.
                val replyMarkup = runCatching { buildActionKeyboard(row, chatId, now) }.getOrNull()
                when (val result = telegramSender.send(item, chatId, resolution?.url, replyMarkup)) {
                    is SendResult.Success -> {
                        outboxRepository.markSent(id)
                        deleteImageFiles(item)
                        // Bind the freshly-created tokens to the message that carries them, and start
                        // listening for presses.
                        onButtonsSent(row, result)
                        // Retire the previous track's message so the chat keeps one live control.
                        onMediaSent(row, item, result, chatId, now)
                        // Best-effort: if the first magic-link attempt missed, queue a background
                        // retry to edit the just-sent message once the link resolves. Fully isolated
                        // from delivery (see the method's runCatching).
                        maybeScheduleLinkRetry(row, resolution, result, chatId, now)
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

    /**
     * Build the inline action keyboard for [row], or null when remote actions are off, the item has no
     * captured notification identity, or it exposes nothing actionable. Creating the tokens here (not
     * in the sender) keeps all persistence in the worker.
     */
    private suspend fun buildActionKeyboard(row: OutboxEntity, chatId: Long, now: Long): String? {
        val enabled = settings.remoteActionsEnabled.first()
        val actions = NotificationActions.decode(row.actionsJson)
        val buttons = if (enabled && row.notificationKey != null) remoteButtons(actions) else emptyList()

        // Diagnostics: explains a forward that arrived with no buttons.
        remoteActionDiag.attach(
            packageName = row.packageName,
            enabled = enabled,
            hasNotificationKey = row.notificationKey != null,
            actionCount = actions.size,
            buttonCount = buttons.size,
            reason = when {
                !enabled -> "remoteActionsDisabled"
                row.notificationKey == null -> "noNotificationKey"
                else -> null
            },
        )

        if (buttons.isEmpty()) return null
        remoteActionKeyboards.purgeExpired(now)
        // A media notification can't be cleared by a listener, so offering Dismiss would be a button
        // that silently does nothing. Its own Stop/Pause controls are the real thing.
        //
        // Prefers the flag recorded at capture time. Asking the LIVE notification, as this used to do
        // exclusively, quietly returns false once the notification is gone — so a media item whose
        // delivery lagged (offline, backoff, a long queue) would arrive with a Dismiss button that
        // cannot work and a "Pause" label that flips underneath the user.
        val ongoingMedia = row.isMedia
            ?: row.notificationKey?.let { actionGateway.isOngoingMedia(it) }
            ?: false
        return remoteActionKeyboards.createKeyboard(
            row = row,
            chatId = chatId,
            now = now,
            includeDismiss = !ongoingMedia,
            // This message is a one-shot forward and never gets re-rendered, so a "Pause" label would
            // be wrong the instant it works. Name the toggle instead.
            stableLabels = ongoingMedia,
        )
    }

    /**
     * After a successful send: bind this row's tokens to the message that hosts the buttons, then kick
     * the burst poller so a press in the next few minutes is picked up. Entirely best-effort — buttons
     * are a convenience and must never affect delivery state.
     */
    private suspend fun onButtonsSent(row: OutboxEntity, result: SendResult.Success) {
        runCatching {
            val messageId = result.keyboardMessageId ?: return
            remoteActionKeyboards.attachToMessage(row.id, messageId)
            TelegramPollWorker.scheduleBurst(workManager)
        }
    }

    /**
     * A media forward supersedes the previous one for that app: record what was just posted and delete
     * the track before it, so a listening session leaves one live control rather than a message per
     * song. Only applies to the ordinary forward path — with "Now playing control" on, media never
     * reaches the outbox at all, so there is nothing here to prune.
     *
     * Best-effort in the same way buttons are: a failure must never affect the delivery just made.
     */
    private suspend fun onMediaSent(
        row: OutboxEntity,
        item: OutboxWithImages,
        result: SendResult.Success,
        chatId: Long,
        now: Long,
    ) {
        runCatching {
            // `isMedia` is null on rows queued before it was recorded; those fall back to asking the
            // live notification, which is what the whole pipeline used to do.
            val isMedia = row.isMedia
                ?: row.notificationKey?.let { actionGateway.isOngoingMedia(it) }
                ?: false
            if (!isMedia) return
            mediaForwardController.recordAndPrune(
                packageName = row.packageName,
                chatId = chatId,
                messageIds = result.messageIds,
                pinMessageId = result.keyboardMessageId ?: result.messageIds.lastOrNull(),
                trackKey = "${row.title.orEmpty()}${row.body.orEmpty()}",
                photoMessage = item.images.isNotEmpty(),
                now = now,
            )
        }
    }

    /**
     * When the first, synchronous magic-link attempt missed but the item was still forwarded, persist
     * a [PendingLinkResolutionEntity] and kick the background [LinkResolveRetryWorker] to re-resolve
     * and edit the sent message later. Gated to exactly the retryable case: a supported, non-opted-out
     * YouTube package, a first attempt that produced no url via [MagicLinkOutcome.NO_MATCH] /
     * [MagicLinkOutcome.FEED_ERROR], a known channel id + non-blank title, and a clean single editable
     * target on the send. Wrapped in [runCatching] so it can NEVER throw into or affect delivery.
     */
    private suspend fun maybeScheduleLinkRetry(
        row: OutboxEntity,
        resolution: MagicLinkResult?,
        sendResult: SendResult.Success,
        chatId: Long,
        now: Long,
    ) {
        runCatching {
            if (resolution == null || resolution.url != null) return
            if (row.packageName !in YouTube.PACKAGES) return
            if (row.packageName in settings.magicLinkDisabledPackages.first()) return
            val outcome = resolution.trace.outcome
            if (outcome != MagicLinkOutcome.NO_MATCH && outcome != MagicLinkOutcome.FEED_ERROR) return
            val channelId = resolution.trace.channelId ?: return
            val videoTitle = (resolution.trace.videoTitle ?: row.body)?.trim()
            if (videoTitle.isNullOrBlank()) return
            val editableMessageId = sendResult.editableMessageId ?: return
            val editableText = sendResult.editableText ?: return

            pendingLinkResolutionDao.insert(
                PendingLinkResolutionEntity(
                    chatId = chatId,
                    messageId = editableMessageId,
                    isCaption = sendResult.editableIsCaption,
                    sentText = editableText,
                    channelId = channelId,
                    videoTitle = videoTitle,
                    attemptCount = 0,
                    nextAttemptAt = now + LinkResolveRetryWorker.FIRST_DELAY_MS,
                    expiresAt = now + LinkResolveRetryWorker.MAX_WINDOW_MS,
                    createdAt = now,
                ),
            )
            LinkResolveRetryWorker.schedule(workManager, LinkResolveRetryWorker.FIRST_DELAY_MS)
        }
    }

    /**
     * Append a `magicLinkTrace` diagnostics record for a supported magic-link item so a missing
     * `Link:` line can be explained (YouTube feed staleness `feedNewestPublished` vs `postTime`, or
     * the Apple Music track+artist that failed to match). Gated on diagnostics being enabled AND the
     * item being a supported package. Entirely best-effort: wrapped so it can NEVER throw into (or
     * otherwise affect) the send path, and it uses only public APIs so it works in RELEASE builds.
     */
    private suspend fun logMagicLinkTrace(row: OutboxEntity, resolution: MagicLinkResult) {
        runCatching {
            if (!settings.diagnosticsEnabled.first()) return
            if (magicLinkKind(row.packageName) == null) return
            val t = resolution.trace
            val json = JSONObject().apply {
                put("kind", "magicLinkTrace")
                put("phase", "initial")
                put("capturedAt", System.currentTimeMillis())
                put("packageName", row.packageName)
                put("postTime", row.postTime)
                put("outcome", t.outcome.name)
                putOpt("service", t.service)
                putOpt("channelId", t.channelId)
                putOpt("videoTitle", t.videoTitle)
                putOpt("feedEntryCount", t.feedEntryCount)
                putOpt("feedNewestPublished", t.feedNewestPublished)
                putOpt("feedOldestPublished", t.feedOldestPublished)
                putOpt("feedNewestTitle", t.feedNewestTitle)
                putOpt("httpStatus", t.httpStatus)
                putOpt("error", t.error)
                putOpt("videoId", t.videoId)
                putOpt("url", t.url)
                put("cacheBusted", t.cacheBusted)
                putOpt("source", t.source)
                put("searchAttempted", t.searchAttempted)
                putOpt("searchResultCount", t.searchResultCount)
                putOpt("searchChannelMatched", t.searchChannelMatched)
                putOpt("mediaTrack", t.mediaTrack)
                putOpt("mediaArtist", t.mediaArtist)
                putOpt("storefront", t.storefront)
                putOpt("matchedTrack", t.matchedTrack)
                putOpt("matchedArtist", t.matchedArtist)
            }
            diagStore.append(ForensicRecord(json.toString()))
        }
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

        /**
         * Upper bound on best-effort magic-link resolution (RSS feed + a ~1MB search-page fallback
         * fetch); expiry here just means "no Link: line" and never delays the forward beyond this.
         */
        private const val LINK_RESOLVE_TIMEOUT_MS = 12_000L

        private const val NO_RECIPIENT_ERROR = "no recipient paired"
    }
}
