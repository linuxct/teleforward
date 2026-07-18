package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.dao.OutboxDao
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [OutboxRepository]: CRUD + lifecycle transitions over the outbox and its image rows,
 * plus image-file cleanup on terminal states. Reads of rows-with-images use the DAO's
 * `@Transaction` queries.
 */
@Singleton
class OutboxRepositoryImpl @Inject constructor(
    private val outboxDao: OutboxDao,
) : OutboxRepository {

    override suspend fun enqueue(item: OutboxEntity, images: List<OutboxImageEntity>): Long {
        // insert IGNOREs on duplicate dedupeKey and returns -1L; propagate that as a drop.
        val id = outboxDao.insert(item)
        if (id != -1L) {
            if (images.isNotEmpty()) {
                outboxDao.insertImages(images.map { it.copy(outboxId = id) })
            }
            return id
        }

        // A duplicate. Before discarding it, check whether it is the *better* half of a pair: a media
        // notification posts a track change with no artwork and re-posts once the cover has loaded,
        // and it is this second post that carries the image. Merging it into the queued row is what
        // turns "every song arrives twice, once bare and once with a cover" into one complete message.
        val merged = runCatching { outboxDao.attachImagesIfUnsent(item.dedupeKey, images) }
            .getOrDefault(false)
        // Nothing references these files if the merge didn't happen, so they would sit in the cache
        // forever — every repeat of an image-bearing notification used to leak one.
        if (!merged) deleteFiles(images)
        return -1L
    }

    private fun deleteFiles(images: List<OutboxImageEntity>) {
        images.forEach { runCatching { File(it.filePath).delete() } }
    }

    override suspend fun getById(id: Long): OutboxWithImages? = outboxDao.getWithImages(id)

    override suspend fun getByDedupeKey(dedupeKey: String): OutboxEntity? =
        outboxDao.getByDedupeKey(dedupeKey)

    override suspend fun getDeliverable(limit: Int): List<OutboxWithImages> =
        outboxDao.getDeliverable(DELIVERABLE_STATUSES, limit)

    override fun observeRecent(limit: Int): Flow<List<OutboxWithImages>> =
        outboxDao.observeRecent(limit)

    override suspend fun markSending(id: Long) = outboxDao.setStatus(id, OutboxStatus.SENDING)

    override suspend fun markSent(id: Long) {
        deleteImageFiles(id)
        outboxDao.setStatus(id, OutboxStatus.SENT)
    }

    override suspend fun markFailed(id: Long, error: String) {
        // Preserve attemptCount/nextAttemptAt while recording the terminal status + error.
        val current = outboxDao.getById(id) ?: return
        outboxDao.updateStatus(
            id = id,
            status = OutboxStatus.FAILED,
            attemptCount = current.attemptCount,
            nextAttemptAt = current.nextAttemptAt,
            lastError = error,
        )
    }

    override suspend fun markExpired(id: Long) {
        deleteImageFiles(id)
        outboxDao.setStatus(id, OutboxStatus.EXPIRED)
    }

    override suspend fun reschedule(id: Long, attemptCount: Int, nextAttemptAt: Long, error: String?) {
        outboxDao.updateStatus(
            id = id,
            status = OutboxStatus.PENDING,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            lastError = error,
        )
    }

    override suspend fun resetStaleSending() =
        outboxDao.resetStatus(from = OutboxStatus.SENDING, to = OutboxStatus.PENDING)

    override suspend fun deleteById(id: Long) {
        // Files are not FK-cascaded; delete them before dropping the row (which cascades image rows).
        deleteImageFiles(id)
        outboxDao.deleteById(id)
    }

    override suspend fun countDeliverable(): Int = outboxDao.countByStatus(DELIVERABLE_STATUSES)

    override suspend fun clearSent() = outboxDao.deleteByStatus(OutboxStatus.SENT)

    override suspend fun clearAll() = outboxDao.clear()

    private suspend fun deleteImageFiles(outboxId: Long) {
        outboxDao.getImages(outboxId).forEach { image ->
            runCatching { File(image.filePath).delete() }
        }
    }

    private companion object {
        /** PENDING plus (stale) SENDING rows are the delivery worker's working set. */
        val DELIVERABLE_STATUSES = listOf(OutboxStatus.PENDING, OutboxStatus.SENDING)
    }
}
