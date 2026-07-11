package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.entity.SeenChannelEntity

@Dao
interface SeenChannelDao {

    @Query("SELECT * FROM seen_channels ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<SeenChannelEntity>>

    @Query("SELECT * FROM seen_channels WHERE packageName = :packageName ORDER BY lastSeen DESC")
    fun observeForPackage(packageName: String): Flow<List<SeenChannelEntity>>

    @Query("SELECT * FROM seen_channels WHERE packageName = :packageName ORDER BY lastSeen DESC")
    suspend fun getForPackage(packageName: String): List<SeenChannelEntity>

    @Query("SELECT DISTINCT packageName FROM seen_channels")
    suspend fun getSeenPackages(): List<String>

    @Query("SELECT DISTINCT packageName FROM seen_channels")
    fun observeSeenPackages(): Flow<List<String>>

    @Query(
        "SELECT MAX(lastSeen) FROM seen_channels WHERE packageName = :packageName",
    )
    suspend fun lastSeenForPackage(packageName: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(channel: SeenChannelEntity): Long

    @Upsert
    suspend fun upsert(channel: SeenChannelEntity)

    /** Update the mutable fields (name/importance/lastSeen) of an already-seen channel. */
    @Query(
        "UPDATE seen_channels SET name = :name, importance = :importance, lastSeen = :lastSeen " +
            "WHERE packageName = :packageName AND channelId = :channelId AND userSerial = :userSerial",
    )
    suspend fun touch(
        packageName: String,
        channelId: String,
        userSerial: Long,
        name: String?,
        importance: Int?,
        lastSeen: Long,
    )

    @Query("DELETE FROM seen_channels")
    suspend fun clear()
}
