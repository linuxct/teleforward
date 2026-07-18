package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import space.linuxct.teleforward.data.db.entity.MediaForwardEntity

@Dao
interface MediaForwardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaForwardEntity): Long

    /**
     * Every media message for [packageName] older than the send that just happened — i.e. exactly the
     * ones to delete.
     *
     * Keyed on time rather than row id because one forward can produce several messages (a media group
     * cannot carry buttons, so those ride a separate message). Excluding a single "keep" id would have
     * deleted the other halves of the very send that triggered the prune.
     *
     * Ordered oldest-first so the chat collapses in a sensible order if several have piled up (a spell
     * offline, or a delete that failed and is only now being retried).
     */
    @Query(
        "SELECT * FROM media_forwards WHERE packageName = :packageName AND sentAt < :keepFrom " +
            "ORDER BY sentAt ASC",
    )
    suspend fun findSuperseded(packageName: String, keepFrom: Long): List<MediaForwardEntity>

    /** Is this one of the messages we posted? Used to recognise our own pin service notices. */
    @Query("SELECT COUNT(*) FROM media_forwards WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun countByMessage(chatId: Long, messageId: Long): Int

    @Query("DELETE FROM media_forwards WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM media_forwards WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun deleteByMessage(chatId: Long, messageId: Long)

    /**
     * Rows too old for Telegram to let a bot delete their messages anyway, so keeping them only
     * guarantees repeated failures.
     */
    @Query("DELETE FROM media_forwards WHERE sentAt <= :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
