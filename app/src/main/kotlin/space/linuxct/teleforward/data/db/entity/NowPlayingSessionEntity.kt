package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single "now playing" message TeleForward maintains for one media app.
 *
 * Media notifications re-post on every track change and every play/pause, so forwarding them
 * normally would mean a message per tick. Instead each app gets one message that is **edited in
 * place**, and this row remembers which message that is so edits survive process death.
 *
 * @property sessionKey the media app's package name — one control message per app.
 * @property messageId the Telegram message being edited.
 * @property trackKey identity of the song itself (title + artist). A **change here means a new
 *   track**, which gets a fresh message so Telegram actually notifies you — the old one is deleted so
 *   only the newest control survives. Null on rows written before this was tracked.
 * @property fingerprint full render signature (track + action labels). Differs from [trackKey] only
 *   for state changes like play ⇄ pause, which are applied as a silent edit instead of a new message.
 *   Identical re-posts (media notifications repeat constantly) are ignored entirely.
 */
@Entity(tableName = "now_playing_sessions")
data class NowPlayingSessionEntity(
    @PrimaryKey val sessionKey: String,
    val chatId: Long,
    val messageId: Long,
    val notificationKey: String,
    val trackKey: String? = null,
    /**
     * True when the control message carries album art, so a state-only change edits its **caption**
     * rather than its text. Null on rows written before artwork was carried.
     */
    val photoMessage: Boolean? = null,
    val fingerprint: String,
    val updatedAt: Long,
)
