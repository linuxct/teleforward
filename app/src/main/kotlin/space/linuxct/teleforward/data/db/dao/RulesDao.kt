package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.data.db.entity.SelectionRuleEntity

@Dao
interface RulesDao {

    @Query("SELECT * FROM selection_rules")
    fun observeAll(): Flow<List<SelectionRuleEntity>>

    @Query("SELECT * FROM selection_rules")
    suspend fun getAll(): List<SelectionRuleEntity>

    @Query("SELECT * FROM selection_rules WHERE packageName = :packageName")
    fun observeForPackage(packageName: String): Flow<List<SelectionRuleEntity>>

    @Query("SELECT * FROM selection_rules WHERE packageName = :packageName")
    suspend fun getForPackage(packageName: String): List<SelectionRuleEntity>

    /** `IS` matches NULL correctly in SQLite (whole-app / channel rule lookups have null columns). */
    @Query(
        "SELECT * FROM selection_rules WHERE packageName = :packageName " +
            "AND channelId IS :channelId AND conversationId IS :conversationId LIMIT 1",
    )
    suspend fun getRule(
        packageName: String,
        channelId: String?,
        conversationId: String?,
    ): SelectionRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: SelectionRuleEntity): Long

    @Update
    suspend fun update(rule: SelectionRuleEntity)

    @Delete
    suspend fun delete(rule: SelectionRuleEntity)

    @Query(
        "DELETE FROM selection_rules WHERE packageName = :packageName " +
            "AND channelId IS :channelId AND conversationId IS :conversationId",
    )
    suspend fun deleteRule(packageName: String, channelId: String?, conversationId: String?)

    /** Delete a conversation rule by its chat identity, regardless of the channel it was seen on. */
    @Query(
        "DELETE FROM selection_rules WHERE packageName = :packageName " +
            "AND conversationId IS :conversationId",
    )
    suspend fun deleteConversationRule(packageName: String, conversationId: String?)

    @Query("DELETE FROM selection_rules WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("DELETE FROM selection_rules")
    suspend fun clear()
}
