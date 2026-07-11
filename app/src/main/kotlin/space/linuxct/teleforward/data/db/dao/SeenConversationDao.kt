package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.entity.SeenConversationEntity

@Dao
interface SeenConversationDao {

    @Query("SELECT * FROM seen_conversations ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<SeenConversationEntity>>

    @Query("SELECT * FROM seen_conversations WHERE packageName = :packageName ORDER BY lastSeen DESC")
    fun observeForPackage(packageName: String): Flow<List<SeenConversationEntity>>

    @Query("SELECT * FROM seen_conversations WHERE packageName = :packageName ORDER BY lastSeen DESC")
    suspend fun getForPackage(packageName: String): List<SeenConversationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(conversation: SeenConversationEntity): Long

    /**
     * Update the mutable fields (title/lastSeen) of an already-seen conversation. A null incoming
     * title is ignored (COALESCE) so a later notification that can't resolve the title never wipes
     * a previously resolved one.
     */
    @Query(
        "UPDATE seen_conversations SET title = COALESCE(:title, title), lastSeen = :lastSeen " +
            "WHERE packageName = :packageName AND channelId = :channelId " +
            "AND conversationId = :conversationId AND userSerial = :userSerial",
    )
    suspend fun touch(
        packageName: String,
        channelId: String,
        conversationId: String,
        userSerial: Long,
        title: String?,
        lastSeen: Long,
    )

    @Query("DELETE FROM seen_conversations")
    suspend fun clear()
}
