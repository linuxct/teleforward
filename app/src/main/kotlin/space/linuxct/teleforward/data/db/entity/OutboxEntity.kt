package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle state of an outbox item as it moves through the delivery pipeline.
 */
enum class OutboxStatus {
    /** Waiting to be delivered. */
    PENDING,

    /** Claimed by a delivery worker (in-flight). Stale SENDING rows are re-tried. */
    SENDING,

    /** Delivered to Telegram (2xx). Image files are deleted on this transition. */
    SENT,

    /** Terminal failure (e.g. 403 recipient unreachable, or max attempts exceeded). */
    FAILED,

    /** Aged out past the configured expiry window. Image files are deleted. */
    EXPIRED,
}

/**
 * A single queued message destined for the paired Telegram chat. Metadata only; the message text
 * is (re)built at send time by the MessageBuilder from these fields.
 *
 * `dedupeKey` is UNIQUE and inserts use OnConflict.IGNORE, giving local idempotency against the
 * notification listener re-posting the same notification.
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val dedupeKey: String,
    val packageName: String,
    val channelId: String?,
    val appLabel: String,
    val channelName: String?,
    val title: String?,
    val body: String?,
    val postTime: Long,
    val status: OutboxStatus,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
)
