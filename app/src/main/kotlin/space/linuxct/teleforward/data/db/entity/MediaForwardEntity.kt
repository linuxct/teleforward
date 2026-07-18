package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A media-playback message this app has posted through the **ordinary forward path** (the one used
 * when "Now playing control" is off), remembered so it can be deleted once a newer one supersedes it.
 *
 * Without this, a listening session leaves one message per track in the chat: the screenshot that
 * prompted this feature showed three Apple Music forwards in five minutes, each with a full-width
 * cover and six buttons. Only the newest is useful — the older ones point at a notification that has
 * already been replaced, so their buttons act on whatever is playing *now*, not on the track their
 * text describes.
 *
 * Deliberately NOT the same thing as [NowPlayingSessionEntity]. That row tracks one message that is
 * *edited in place* for the whole session; these rows track a **stream** of one-shot forwards, of
 * which all but the last are deleted. The two modes coexist and must never share state.
 *
 * Every emitted message is recorded rather than only the newest, so a delete that fails (offline,
 * Telegram hiccup) is simply retried the next time a track changes instead of stranding a message
 * in the chat forever.
 *
 * @property packageName the media app; retention is per-app, so a second player's control is not
 *   deleted by the first player's next track.
 * @property messageId the Telegram message to delete when this row is superseded.
 * @property photoMessage true when the message carries cover art, purely for diagnostics.
 * @property trackKey identity of the song this message describes, for diagnostics.
 */
@Entity(
    tableName = "media_forwards",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["chatId", "messageId"], unique = true),
        Index(value = ["sentAt"]),
    ],
)
data class MediaForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val chatId: Long,
    val messageId: Long,
    val trackKey: String? = null,
    val photoMessage: Boolean = false,
    val sentAt: Long,
)
