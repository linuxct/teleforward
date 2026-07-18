package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.dao.MediaForwardDao
import space.linuxct.teleforward.data.db.dao.NowPlayingSessionDao
import space.linuxct.teleforward.data.telegram.dto.TgMessage
import space.linuxct.teleforward.util.BoundedCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps whatever is playing pinned to the top of the chat, and cleans up after itself.
 *
 * The point is that the current track stays reachable no matter how many notifications arrive under
 * it — Telegram's pin bar is the only "stick this somewhere I can find it" primitive a bot has.
 *
 * ## The service-message problem
 *
 * Pinning emits a "pinned a message" service line into the chat, **including in a private chat**, and
 * `disable_notification` does not suppress it — that flag only silences the push, and in a 1:1 chat it
 * is a no-op entirely. Since the pin moves on every track change, left alone this would leave one grey
 * line per song, which is worse than the clutter it was meant to solve.
 *
 * So each pin is remembered, and when its service notice comes back through the poller it is deleted.
 * The Bot API explicitly permits deleting service messages, and a bot may delete messages in a private
 * chat, so this is a supported path rather than a trick.
 *
 * Two deliberate asymmetries worth knowing:
 *  - **Unpinning is silent.** It emits no service message, so the app unpins freely and only ever pays
 *    the cost on the way in.
 *  - **Unpin before delete.** Deleting a pinned message is widely held to unpin it, but that rests on
 *    client-side evidence rather than documented behaviour; an explicit unpin costs one silent call
 *    and removes the need to rely on it.
 *
 * Everything is best-effort. In a group chat a bot needs `can_pin_messages` and will simply be
 * refused, which is fine: the messages still arrive, they just don't get pinned.
 */
@Singleton
class ChatPinner @Inject constructor(
    private val api: TelegramApi,
    private val mediaForwardDao: MediaForwardDao,
    private val nowPlayingSessionDao: NowPlayingSessionDao,
) {

    /**
     * Message ids this app has pinned, so their service notices can be recognised on the way back.
     *
     * Hard-capped: only the newest handful can still have an unprocessed notice in flight, and an
     * unbounded map here would grow for the length of a listening session.
     */
    private val pinnedByUs = BoundedCache<Long, Long>(MAX_TRACKED_PINS)

    /** Pin [messageId], replacing whatever this app had pinned before. */
    suspend fun pin(chatId: Long, messageId: Long) {
        val ok = runCatching { api.pinChatMessage(chatId = chatId, messageId = messageId) }
            .getOrNull()?.isSuccessful == true
        if (ok) pinnedByUs[messageId] = chatId
    }

    /** Unpin [messageId]. Silent — no service message — so it is safe to call speculatively. */
    suspend fun unpin(chatId: Long, messageId: Long) {
        runCatching { api.unpinChatMessage(chatId = chatId, messageId = messageId) }
        pinnedByUs.remove(messageId)
    }

    /**
     * If [message] is the service notice for one of our own pins, delete it.
     *
     * @return true when it was ours and was handled, so the caller can skip further processing.
     *
     * Ownership is checked three ways: an in-memory record of what this process pinned, plus both
     * tables that can own a pinned message — `media_forwards` for ordinary media forwards and
     * `now_playing_sessions` for the live control. The two table lookups are what make this survive a
     * process restart. A notice for a message the *user* pinned is never touched.
     *
     * Best-effort by nature — the notice can only be deleted while something is polling. It is not
     * lost if we are not: Telegram queues updates for 24h and a bot can delete a message for 48h, so
     * a catch-up poll still tidies it.
     */
    suspend fun consumePinNotice(message: TgMessage): Boolean {
        val pinnedId = message.pinnedMessage?.messageId?.takeIf { it > 0L } ?: return false
        val chatId = message.chat.id
        val ours = pinnedByUs[pinnedId] == chatId ||
            runCatching { mediaForwardDao.countByMessage(chatId, pinnedId) > 0 }.getOrDefault(false) ||
            // Now-playing controls live in their own table; without this a restart between the pin and
            // the poll leaves that notice stranded in the chat forever.
            runCatching { nowPlayingSessionDao.countByMessage(chatId, pinnedId) > 0 }.getOrDefault(false)
        if (!ours) return false
        runCatching { api.deleteMessage(chatId = chatId, messageId = message.messageId) }
        return true
    }

    private companion object {
        /** Only the most recent pins can still have a notice in flight. */
        const val MAX_TRACKED_PINS = 16
    }
}
