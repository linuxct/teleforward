package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Binds an inline button (or a reply target) on a sent Telegram message back to a device notification
 * action.
 *
 * Telegram caps a button's `callback_data` at **64 bytes**, far too small for a
 * `StatusBarNotification.key`, so the button carries only the opaque [token] and everything real
 * lives here. A random token (rather than the row id) means a recycled autoincrement id can never
 * make a stale button fire the wrong action.
 *
 * Rows are also the source of truth for *which buttons a message has*, so the magic-link edit can
 * re-attach the same keyboard (an edit without `reply_markup` would strip it).
 *
 * @property token the opaque value carried in `callback_data`.
 * @property kind which [space.linuxct.teleforward.domain.RemoteActionKind] this button performs.
 * @property notificationKey `StatusBarNotification.key` of the device notification to act on.
 * @property actionIndex index into `Notification.actions`; -1 for DISMISS (no source action needed).
 * @property semantic preferred action locator at fire time (survives action reordering); 0 if unknown.
 * @property label the button's rendered text, so the keyboard can be rebuilt verbatim.
 * @property position ordering of the button within the row.
 * @property messageId the Telegram message the button is attached to; filled in after the send, and
 *   used both to rebuild the keyboard and to match a user's reply back to this notification.
 * @property expiresAt rows are swept after this; buttons on very old messages simply stop working.
 */
@Entity(
    tableName = "callback_tokens",
    indices = [
        Index(value = ["token"], unique = true),
        Index(value = ["chatId", "messageId"]),
        Index(value = ["expiresAt"]),
    ],
)
data class CallbackTokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val token: String,
    val kind: String,
    val notificationKey: String,
    val actionIndex: Int,
    val semantic: Int,
    val label: String,
    val position: Int,
    val chatId: Long,
    val messageId: Long? = null,
    val outboxId: Long,
    val expiresAt: Long,
    val createdAt: Long,
) {
    /**
     * True for a button on a long-lived control (the now-playing message) rather than a one-shot
     * forwarded notification. Such a message stays useful after a press — you pause, then want to
     * play again — so its keyboard must never be collapsed; `NowPlayingController` owns it and
     * rebuilds it as the player's own buttons change.
     *
     * Derived from [outboxId]: now-playing tokens belong to no outbox row.
     */
    val isPersistentControl: Boolean get() = outboxId == NO_OUTBOX

    companion object {
        /** [outboxId] for tokens that don't belong to a forwarded outbox row. */
        const val NO_OUTBOX = 0L
    }
}
