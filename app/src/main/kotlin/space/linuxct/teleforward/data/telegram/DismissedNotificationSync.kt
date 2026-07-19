package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.dao.CallbackTokenDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retires a forwarded message's buttons the moment its notification leaves the phone.
 *
 * ## Why this exists
 *
 * Pressing a button requires the app to be listening at that instant — Telegram stops accepting an
 * answer seconds after the press, so a poll that arrives later can perform the action but can never
 * light up the button. That asymmetry is unavoidable without a server.
 *
 * But it only binds one direction. **Outbound calls always work.** The phone knows the instant a
 * notification is dismissed, and can say so immediately, with nothing listening and no connection
 * held. So the question the user actually cares about — "is this still there, or am I pressing a dead
 * button?" — is answerable for free, even though the *action* is not.
 *
 * The buttons are replaced with a single settled label rather than removed outright, so the message
 * reads as resolved instead of merely losing its controls.
 */
@Singleton
class DismissedNotificationSync @Inject constructor(
    private val callbackTokenDao: CallbackTokenDao,
    private val api: TelegramApi,
    private val keyboards: RemoteActionKeyboards,
    private val strings: TelegramStrings,
) {

    /**
     * Mark every message still offering buttons for [notificationKey] as handled on the device.
     *
     * Entirely best-effort: this is a courtesy, and a failure just leaves the buttons in place, which
     * is exactly the behaviour that existed before.
     */
    suspend fun onDismissed(notificationKey: String) {
        runCatching {
            val tokens = callbackTokenDao.findByNotificationKey(notificationKey)
            if (tokens.isEmpty()) return

            // One edit per message, not per token — a keyboard has several tokens on one message.
            tokens.mapNotNull { token -> token.messageId?.let { token.chatId to it } }
                .distinct()
                .forEach { (chatId, messageId) ->
                    runCatching {
                        api.editMessageReplyMarkup(
                            chatId = chatId,
                            messageId = messageId,
                            replyMarkup = keyboards.resolvedMarkup(strings.dismissedOnPhone),
                        )
                    }
                }

            // Drop the tokens too. The buttons are gone from the client, but a press already in flight
            // (or a client that hasn't refreshed) must not still be able to drive the device.
            tokens.forEach { token ->
                runCatching { callbackTokenDao.deleteForMessage(token.chatId, token.messageId ?: return@forEach) }
            }
        }
    }
}
