package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.dao.MediaForwardDao
import space.linuxct.teleforward.data.db.entity.MediaForwardEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the chat down to **one live media message per player** when forwarding media notifications
 * the ordinary way (i.e. with "Now playing control" off).
 *
 * A listening session otherwise leaves one message per track: full-width cover, six buttons, times
 * however many songs. Worse, the older ones are actively misleading — their buttons act on whatever
 * is playing *now*, not on the track their text describes, because the notification they point at was
 * long since replaced.
 *
 * So each new media forward retires the previous one for that app. This deliberately mirrors what the
 * now-playing control does, but arrives at it from the other direction: now-playing owns one message
 * and edits it, whereas this posts a genuinely new message per track (which is what makes Telegram
 * notify you that the song changed) and cleans up behind itself.
 *
 * ## Why per-app rather than one message overall
 *
 * Retention is keyed by package, matching the now-playing control. If a video app posts a transport
 * notification while music is playing, deleting one because the other advanced a track would throw
 * away a control the user is still using. With a single player — the normal case — "the newest per
 * app" and "the newest overall" are the same message.
 *
 * Everything here is best-effort: a failed delete leaves the row in place and is retried on the next
 * track, and nothing may ever affect delivery of the message that triggered it.
 */
@Singleton
class MediaForwardController @Inject constructor(
    private val dao: MediaForwardDao,
    /**
     * Used directly rather than through [TelegramSender], whose `deleteMessage` returns Unit and
     * swallows failures — indistinguishable from success, which would silently defeat the retry below.
     */
    private val api: TelegramApi,
    private val keyboards: RemoteActionKeyboards,
    private val pinner: ChatPinner,
) {

    /**
     * Record the message(s) a media forward just produced, then delete the ones they supersede.
     *
     * [messageIds] is a list because one forward can span several messages; all of them are kept and
     * all of the previous track's are removed.
     */
    suspend fun recordAndPrune(
        packageName: String,
        chatId: Long,
        messageIds: List<Long>,
        /** The message to pin — the one carrying the controls, so the pin is useful and not just a label. */
        pinMessageId: Long?,
        trackKey: String?,
        photoMessage: Boolean,
        now: Long,
    ) {
        if (messageIds.isEmpty()) return
        runCatching {
            messageIds.forEach { messageId ->
                dao.insert(
                    MediaForwardEntity(
                        packageName = packageName,
                        chatId = chatId,
                        messageId = messageId,
                        trackKey = trackKey,
                        photoMessage = photoMessage,
                        sentAt = now,
                    ),
                )
            }
            // Rows whose messages Telegram will no longer let a bot delete. Dropping them stops the
            // app retrying an impossible delete on every single track for the rest of the session.
            runCatching { dao.deleteOlderThan(now - UNDELETABLE_AFTER_MS) }

            for (stale in dao.findSuperseded(packageName, keepFrom = now)) {
                retire(stale)
            }
            // Pin last, so the previous track has already been unpinned and deleted. Pinning first
            // would briefly leave two pins and produce a second service notice to clean up.
            pinMessageId?.let { pinner.pin(chatId, it) }
        }
    }

    /**
     * Delete one superseded message and forget it.
     *
     * Its callback tokens go first: if the delete succeeds but the token rows survive, a press against
     * a client that hasn't caught up yet would still drive the device from a message the user can no
     * longer see. The row is only dropped once the message is actually gone, so a delete that fails
     * while offline is retried rather than silently abandoned.
     */
    private suspend fun retire(stale: MediaForwardEntity) {
        runCatching { keyboards.clearKeyboardForMessage(stale.chatId, stale.messageId) }
        // Unpin explicitly rather than relying on the delete to do it implicitly. Costs one silent
        // call and means a delete that fails can't strand a pin pointing at a dead message.
        pinner.unpin(stale.chatId, stale.messageId)
        val deleted = runCatching {
            api.deleteMessage(chatId = stale.chatId, messageId = stale.messageId).isSuccessful
        }.getOrDefault(false)
        // Only forget it once the message is really gone; a delete that failed while offline is
        // retried on the next track rather than leaving the message stranded in the chat forever.
        if (deleted) runCatching { dao.delete(stale.id) }
    }

    private companion object {
        /** Telegram refuses to let a bot delete its own messages beyond roughly this age. */
        const val UNDELETABLE_AFTER_MS = 47L * 60L * 60L * 1000L
    }
}
