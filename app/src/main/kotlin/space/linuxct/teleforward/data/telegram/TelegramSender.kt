package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.entity.OutboxWithImages

/**
 * Delivers an outbox item to a Telegram chat: builds the message via [MessageBuilder], chooses the
 * API call from the [SendPlan], performs it through [TelegramApi], and maps the outcome to a
 * [SendResult] (2xx → Success, 429 → RetryAfter, 403 → Terminal, 400 → BadRequest, network/5xx →
 * Transient). See plan T4.
 */
interface TelegramSender {

    /**
     * Build + choose + send [item] to [chatId], returning the classified outcome. When [extraLink] is
     * non-null it is appended to the message as a final `Link: <url>` line.
     *
     * [replyMarkup] is a serialized `InlineKeyboardMarkup` whose buttons are rendered under the sent
     * message. Media groups cannot carry buttons, so for those plans the keyboard rides the preceding
     * text message (or a short follow-up); the hosting message is reported as
     * [SendResult.Success.keyboardMessageId]. Attaching buttons is best-effort and never fails a send.
     */
    suspend fun send(
        item: OutboxWithImages,
        chatId: Long,
        extraLink: String? = null,
        replyMarkup: String? = null,
    ): SendResult

    /** Send a fixed test message to [chatId] (used by pairing / settings "Send test"). */
    suspend fun sendTestMessage(chatId: Long, text: String): SendResult

    /**
     * Send a standalone message, optionally with inline buttons. Used by the "now playing" control,
     * which lives outside the outbox because media notifications update constantly.
     */
    suspend fun sendMessage(chatId: Long, text: String, replyMarkup: String? = null): SendResult

    /**
     * Send a standalone photo with [caption], optionally with inline buttons. Used by the now-playing
     * control to carry album art; the artwork only changes when the track does, and a track change
     * sends a fresh message, so the photo never needs replacing in place.
     */
    suspend fun sendPhotoMessage(
        chatId: Long,
        imagePath: String,
        caption: String,
        replyMarkup: String? = null,
    ): SendResult

    /** Replace a photo message's caption and inline buttons (the text counterpart is [editMessage]). */
    suspend fun editCaption(
        chatId: Long,
        messageId: Long,
        caption: String,
        replyMarkup: String? = null,
    ): SendResult

    /**
     * Replace a message's text AND its inline buttons. The keyboard must be re-sent on every edit or
     * Telegram drops it — and for a media control the buttons genuinely change (Play ⇄ Pause).
     */
    suspend fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: String? = null,
    ): SendResult

    /**
     * Delete one of the bot's own messages. Best-effort: Telegram only allows this for ~48h, and a
     * failure is not worth surfacing (the caller is tidying up, not delivering).
     */
    suspend fun deleteMessage(chatId: Long, messageId: Long)

    /**
     * Append a resolved `Link: <url>` line to an already-sent message by editing it — the background
     * magic-link fallback. Rebuilds the target text via [MessageBuilder.appendLink] from [currentText]
     * (the exact caption/text sent, without a link) and edits via editMessageCaption when [isCaption]
     * else editMessageText. Result mapping: 2xx / "message is not modified" → Success; "message to
     * edit not found" / "MESSAGE_ID_INVALID" / "can't be edited" → Terminal (give up); 429 →
     * RetryAfter; other 400 → BadRequest; network/5xx → Transient.
     */
    suspend fun editAppendLink(
        chatId: Long,
        messageId: Long,
        isCaption: Boolean,
        currentText: String,
        url: String,
        /**
         * The message's existing keyboard, re-sent verbatim. Telegram drops a message's inline buttons
         * when an edit omits `reply_markup`, so this must be supplied for a message that has them.
         */
        replyMarkup: String? = null,
    ): SendResult
}
