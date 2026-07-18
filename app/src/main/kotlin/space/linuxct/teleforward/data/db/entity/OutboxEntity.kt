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
    /**
     * Conversation shortcut id (WhatsApp/Messages-style per-chat identity, e.g. a WhatsApp
     * `…@s.whatsapp.net` / `…@lid`); null for non-conversation items. Used by WhatsApp magic-link
     * reconstruction (a phone-JID yields the number directly).
     */
    val conversationId: String? = null,
    /**
     * The message sender's contact uri (`content://com.android.contacts/…`) when the notification is a
     * 1:1 MessagingStyle conversation; null otherwise. Lets the opt-in WhatsApp resolver recover a
     * saved contact's phone (for `@lid` chats that hide the number). Never contains the number itself.
     */
    val senderContactUri: String? = null,
    /** YouTube channel id (`UC…`) for magic-link reconstruction; null for non-YouTube items. */
    val youtubeChannelId: String? = null,
    /**
     * YouTube video id (11 chars) for magic-link reconstruction. Live-stream/premiere notifications
     * key themselves by the video id, so the watch url is built directly — no feed/search lookup and
     * no edit-after-send retry. Null for ordinary uploads (which carry [youtubeChannelId] instead).
     */
    val youtubeVideoId: String? = null,
    /**
     * `StatusBarNotification.key` — the device-side identity of the notification this row came from.
     * Lets a remote button press (from Telegram) find the still-posted notification again to dismiss
     * it or fire one of its actions. Null for pre-feature rows.
     */
    val notificationKey: String? = null,
    /**
     * The notification's action buttons as compact JSON (see
     * [space.linuxct.teleforward.domain.NotificationActions]); null when it exposed none. Metadata
     * only — the `PendingIntent`s are re-resolved from the live notification at action time.
     */
    val actionsJson: String? = null,
    /**
     * Tier-0 harvested `http`/`https` links found anywhere in the notification, stored newline-joined
     * (URLs contain no newlines); null/blank when none. Appended at send time to any that aren't
     * already inline in the body.
     */
    val extractedLinks: String? = null,
    val postTime: Long,
    val status: OutboxStatus,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
)
