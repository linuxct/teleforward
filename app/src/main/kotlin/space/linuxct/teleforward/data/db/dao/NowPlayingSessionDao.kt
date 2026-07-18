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

    /** Sessions older than [cutoff]; playback that ended without us noticing leaves one behind. */
    @Query("SELECT * FROM now_playing_sessions WHERE updatedAt <= :cutoff")
    suspend fun findStale(cutoff: Long): List<NowPlayingSessionEntity>
}
