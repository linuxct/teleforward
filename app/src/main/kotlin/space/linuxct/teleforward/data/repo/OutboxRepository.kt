package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.OutboxWithImages

/**
 * CRUD + state transitions over the outbox (and its image rows). Consumed by [IntakeRepository]
 * (enqueue), the delivery worker (drain + transitions), and the delivery-log UI (observe).
 */
interface OutboxRepository {

    /**
     * Insert an outbox row + its images atomically. Returns the new row id, or -1L if the
     * dedupeKey already existed (OnConflict.IGNORE) — i.e. a duplicate that was dropped.
     */
    suspend fun enqueue(item: OutboxEntity, images: List<OutboxImageEntity>): Long

    suspend fun getById(id: Long): OutboxWithImages?

    suspend fun getByDedupeKey(dedupeKey: String): OutboxEntity?

    /** Oldest-first deliverable batch (PENDING + stale SENDING) capped at [limit]. */
    suspend fun getDeliverable(limit: Int): List<OutboxWithImages>

    fun observeRecent(limit: Int): Flow<List<OutboxWithImages>>

    suspend fun markSending(id: Long)

    /** Mark delivered and delete the associated image files. */
    suspend fun markSent(id: Long)

    suspend fun markFailed(id: Long, error: String)

    /** Mark expired and delete the associated image files. */
    suspend fun markExpired(id: Long)

    /** Return to PENDING with an incremented attempt and a future [nextAttemptAt]. */
    suspend fun reschedule(id: Long, attemptCount: Int, nextAttemptAt: Long, error: String?)

    /** Reset rows left in SENDING (e.g. after a crash) back to PENDING. */
    suspend fun resetStaleSending()

    suspend fun deleteById(id: Long)

    suspend fun countDeliverable(): Int

    /** Delete SENT rows (log cleanup). */
    suspend fun clearSent()

    suspend fun clearAll()
}
