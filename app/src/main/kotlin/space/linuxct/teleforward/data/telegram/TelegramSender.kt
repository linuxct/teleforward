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
     */
    suspend fun send(item: OutboxWithImages, chatId: Long, extraLink: String? = null): SendResult

    /** Send a fixed test message to [chatId] (used by pairing / settings "Send test"). */
    suspend fun sendTestMessage(chatId: Long, text: String): SendResult

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
    ): SendResult
}
