package space.linuxct.teleforward.data.repo

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.domain.NotificationActions
import space.linuxct.teleforward.domain.RawNotification
import space.linuxct.teleforward.work.DeliveryWorker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single write path from the notification listener into the delivery pipeline. Maps a
 * [RawNotification] to a PENDING [OutboxEntity] (+ image rows), deduplicates via
 * [OutboxRepository.enqueue] (unique dedupeKey), and — only when a new row is created — kicks the
 * unique drain worker.
 */
@Singleton
class IntakeRepositoryImpl @Inject constructor(
    private val outboxRepository: OutboxRepository,
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
) : IntakeRepository {

    override suspend fun enqueue(notification: RawNotification): IntakeResult {
        val now = System.currentTimeMillis()
        val entity = OutboxEntity(
            dedupeKey = notification.dedupeKey,
            packageName = notification.packageName,
            channelId = notification.channelId,
            appLabel = notification.appLabel,
            channelName = notification.channelName,
            title = notification.title,
            body = notification.body,
            conversationId = notification.conversationId,
            senderContactUri = notification.senderContactUri,
            youtubeChannelId = notification.youtubeChannelId,
            youtubeVideoId = notification.youtubeVideoId,
            notificationKey = notification.key,
            actionsJson = NotificationActions.encode(notification.actions),
            isMedia = notification.isMedia,
            isGroupConversation = notification.isGroupConversation,
            discordMessageId = notification.discordMessageId,
            telegramDismissalId = notification.telegramDismissalId,
            // Store the harvested links newline-joined (URLs contain no newlines); null when none.
            extractedLinks = notification.extractedLinks
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n"),
            postTime = notification.postTime,
            status = OutboxStatus.PENDING,
            attemptCount = 0,
            nextAttemptAt = now,
            lastError = null,
            createdAt = now,
        )
        val images = notification.imagePaths.map { path -> path.toImageEntity() }

        val outboxId = outboxRepository.enqueue(entity, images)
        if (outboxId == -1L) return IntakeResult.Duplicate

        scheduleDrain()
        return IntakeResult.Enqueued(outboxId)
    }

    /** Enqueue (KEEP) the unique network-gated drain worker; coalesces bursts into one run. */
    private suspend fun scheduleDrain() {
        // Honour the wifi-only setting: unmetered (Wi-Fi) when set, otherwise any connected network.
        val networkType =
            if (settingsRepository.wifiOnly.first()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(DRAIN_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Rebuild an [OutboxImageEntity] from a persisted image path. The [RawNotification] carries only
     * paths (mime/size/kind are dropped upstream), so size is read back from disk and mime/kind are
     * best-effort defaults (images are JPEG-compressed on extraction). outboxId is assigned by
     * [OutboxRepository.enqueue].
     */
    private fun String.toImageEntity(): OutboxImageEntity {
        val file = File(this)
        return OutboxImageEntity(
            outboxId = 0L,
            filePath = this,
            mime = guessMime(this),
            sizeBytes = file.length(),
            kind = OutboxImageKind.OTHER,
        )
    }

    private fun guessMime(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

    private companion object {
        const val DRAIN_WORK_NAME = "teleforward_outbox_drain"
    }
}
