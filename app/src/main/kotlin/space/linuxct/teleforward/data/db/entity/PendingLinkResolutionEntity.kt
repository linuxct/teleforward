package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A magic-link resolution still owed after an item was already forwarded WITHOUT a link (the first,
 * ~8s-bounded resolve attempt missed). The background [space.linuxct.teleforward.work.LinkResolveRetryWorker]
 * re-resolves the video from ([channelId], [videoTitle]) and, on success, EDITs the already-sent
 * Telegram message ([chatId] + [messageId]) to append a `Link:` line to [sentText].
 *
 * Persisted so the retry survives process death. [nextAttemptAt] gates when the row becomes due,
 * [expiresAt] bounds the whole effort, and [attemptCount] the number of resolve tries spent.
 */
@Entity(
    tableName = "pending_link_resolution",
    indices = [Index(value = ["nextAttemptAt"])],
)
data class PendingLinkResolutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Paired recipient chat the message was sent to. */
    val chatId: Long,
    /** Telegram message id of the editable primary (the message carrying the body/caption). */
    val messageId: Long,
    /** Whether [sentText] is a media caption (editMessageCaption) vs message text (editMessageText). */
    val isCaption: Boolean,
    /** The exact caption/text that was sent, WITHOUT a magic link; the edit rebuilds from this. */
    val sentText: String,
    /** YouTube channel id (`UC…`) to re-resolve against. */
    val channelId: String,
    /** Video title (notification body) matched against the fresh uploads feed. */
    val videoTitle: String,
    /** Number of resolve attempts already spent on this row. */
    val attemptCount: Int,
    /** Epoch millis before which the row is not yet due. */
    val nextAttemptAt: Long,
    /** Epoch millis after which the row is abandoned regardless of attempts. */
    val expiresAt: Long,
    val createdAt: Long,
)
