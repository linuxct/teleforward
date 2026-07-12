package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import space.linuxct.teleforward.data.db.entity.PendingLinkResolutionEntity

/**
 * Persistence for the magic-link edit-after-send retry queue
 * ([PendingLinkResolutionEntity]). Drained by the background retry worker.
 */
@Dao
interface PendingLinkResolutionDao {

    @Insert
    suspend fun insert(row: PendingLinkResolutionEntity): Long

    /** Rows whose [PendingLinkResolutionEntity.nextAttemptAt] has arrived, soonest first. */
    @Query(
        "SELECT * FROM pending_link_resolution WHERE nextAttemptAt <= :now " +
            "ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    suspend fun getDue(now: Long, limit: Int): List<PendingLinkResolutionEntity>

    /** Earliest scheduled attempt across all rows (for computing the next worker delay); null if empty. */
    @Query("SELECT MIN(nextAttemptAt) FROM pending_link_resolution")
    suspend fun getEarliestNextAttempt(): Long?

    @Update
    suspend fun update(row: PendingLinkResolutionEntity)

    /** Bump a row's attempt bookkeeping + reschedule its next due time. */
    @Query(
        "UPDATE pending_link_resolution SET attemptCount = :attemptCount, " +
            "nextAttemptAt = :nextAttemptAt WHERE id = :id",
    )
    suspend fun updateSchedule(id: Long, attemptCount: Int, nextAttemptAt: Long)

    @Query("DELETE FROM pending_link_resolution WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Drop rows that aged out of their [PendingLinkResolutionEntity.expiresAt] window. */
    @Query("DELETE FROM pending_link_resolution WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("SELECT COUNT(*) FROM pending_link_resolution")
    suspend fun count(): Int
}
