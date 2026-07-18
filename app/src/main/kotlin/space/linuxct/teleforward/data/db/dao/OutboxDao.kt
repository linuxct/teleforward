package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.data.db.entity.OutboxWithImages

@Dao
interface OutboxDao {

    /** Returns the new row id, or -1L if the dedupeKey already existed (OnConflict.IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: OutboxEntity): Long

    /**
     * Give an already-queued, not-yet-sent row the images a later duplicate arrived with.
     *
     * This exists for one specific shape: a media notification announces a track change immediately
     * with no album art, then re-posts moments later once the art has loaded. Both posts describe the
     * same track and now share a dedupeKey, so the second is dropped — but it is the one carrying the
     * cover, and dropping it outright would mean every track arrived without artwork.
     *
     * Guarded so it can only ever fill a gap, never rewrite history: the row must still be PENDING
     * (an item already being sent has had its images read) and must have none of its own.
     *
     * @return true when the images were attached, so the caller knows not to delete their files.
     */
    @Transaction
    suspend fun attachImagesIfUnsent(dedupeKey: String, images: List<OutboxImageEntity>): Boolean {
        if (images.isEmpty()) return false
        val row = getByDedupeKey(dedupeKey) ?: return false
        if (row.status != OutboxStatus.PENDING) return false
        if (getImages(row.id).isNotEmpty()) return false
        insertImages(images.map { it.copy(outboxId = row.id) })
        return true
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImages(images: List<OutboxImageEntity>): List<Long>

    @Update
    suspend fun update(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun getById(id: Long): OutboxEntity?

    @Transaction
    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun getWithImages(id: Long): OutboxWithImages?

    @Query("SELECT * FROM outbox WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun getByDedupeKey(dedupeKey: String): OutboxEntity?

    /** Oldest-first batch of deliverable rows (typically PENDING + stale SENDING). */
    @Transaction
    @Query(
        "SELECT * FROM outbox WHERE status IN (:statuses) ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun getDeliverable(statuses: List<OutboxStatus>, limit: Int): List<OutboxWithImages>

    @Transaction
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OutboxWithImages>>

    @Query("SELECT * FROM outbox_images WHERE outboxId = :outboxId")
    suspend fun getImages(outboxId: Long): List<OutboxImageEntity>

    @Query(
        "UPDATE outbox SET status = :status, attemptCount = :attemptCount, " +
            "nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE id = :id",
    )
    suspend fun updateStatus(
        id: Long,
        status: OutboxStatus,
        attemptCount: Int,
        nextAttemptAt: Long,
        lastError: String?,
    )

    @Query("UPDATE outbox SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: OutboxStatus)

    /** Reset in-flight rows (e.g. left SENDING after a crash) back to a deliverable status. */
    @Query("UPDATE outbox SET status = :to WHERE status = :from")
    suspend fun resetStatus(from: OutboxStatus, to: OutboxStatus)

    @Query("SELECT COUNT(*) FROM outbox WHERE status IN (:statuses)")
    suspend fun countByStatus(statuses: List<OutboxStatus>): Int

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM outbox WHERE status = :status")
    suspend fun deleteByStatus(status: OutboxStatus)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
