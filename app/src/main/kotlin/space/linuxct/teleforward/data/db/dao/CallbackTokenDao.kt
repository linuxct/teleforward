package space.linuxct.teleforward.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import space.linuxct.teleforward.data.db.entity.CallbackTokenEntity

@Dao
interface CallbackTokenDao {

    @Insert
    suspend fun insert(entity: CallbackTokenEntity): Long

    /** Resolve a pressed button's `callback_data` back to its action. */
    @Query("SELECT * FROM callback_tokens WHERE token = :token LIMIT 1")
    suspend fun findByToken(token: String): CallbackTokenEntity?

    /** Every button attached to a message, in render order — used to rebuild the keyboard on edit. */
    @Query(
        "SELECT * FROM callback_tokens WHERE chatId = :chatId AND messageId = :messageId " +
            "ORDER BY position ASC",
    )
    suspend fun findByMessage(chatId: Long, messageId: Long): List<CallbackTokenEntity>

    /**
     * The reply target for a message the user replied to: the notification whose RemoteInput action
     * should receive the typed text.
     */
    @Query(
        "SELECT * FROM callback_tokens WHERE chatId = :chatId AND messageId = :messageId " +
            "AND kind = :kind LIMIT 1",
    )
    suspend fun findByMessageAndKind(chatId: Long, messageId: Long, kind: String): CallbackTokenEntity?

    /** Attach freshly-created tokens to the Telegram message they were sent with. */
    @Query("UPDATE callback_tokens SET messageId = :messageId WHERE outboxId = :outboxId AND messageId IS NULL")
    suspend fun attachToMessage(outboxId: Long, messageId: Long)

    /** TTL sweep; stale buttons stop resolving rather than firing something unexpected. */
    @Query("DELETE FROM callback_tokens WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM callback_tokens WHERE outboxId = :outboxId")
    suspend fun deleteForOutbox(outboxId: Long)

    /** Clear a message's buttons before re-creating them (a media control's buttons change constantly). */
    @Query("DELETE FROM callback_tokens WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun deleteForMessage(chatId: Long, messageId: Long)

    @Query("SELECT COUNT(*) FROM callback_tokens")
    suspend fun count(): Int
}
