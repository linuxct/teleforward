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
