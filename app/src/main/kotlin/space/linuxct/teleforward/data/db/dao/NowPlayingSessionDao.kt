package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import space.linuxct.teleforward.data.db.entity.NowPlayingSessionEntity

@Dao
interface NowPlayingSessionDao {

    @Upsert
    suspend fun upsert(entity: NowPlayingSessionEntity)

    @Query("SELECT * FROM now_playing_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun find(sessionKey: String): NowPlayingSessionEntity?

    @Query("DELETE FROM now_playing_sessions WHERE sessionKey = :sessionKey")
    suspend fun delete(sessionKey: String)

    /**
     * Is this one of the control messages we posted? Lets a pin service notice be recognised as ours
     * after a process restart, when the in-memory record of what was pinned is gone.
     */
    @Query("SELECT COUNT(*) FROM now_playing_sessions WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun countByMessage(chatId: Long, messageId: Long): Int

    /** Sessions older than [cutoff]; playback that ended without us noticing leaves one behind. */
    @Query("SELECT * FROM now_playing_sessions WHERE updatedAt <= :cutoff")
    suspend fun findStale(cutoff: Long): List<NowPlayingSessionEntity>
}
