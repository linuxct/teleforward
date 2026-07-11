package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.entity.OutboxWithImages

/**
 * Delivers an outbox item to a Telegram chat: builds the message via [MessageBuilder], chooses the
 * API call from the [SendPlan], performs it through [TelegramApi], and maps the outcome to a
 * [SendResult] (2xx → Success, 429 → RetryAfter, 403 → Terminal, 400 → BadRequest, network/5xx →
 * Transient). See plan T4.
 */
interface TelegramSender {

    /** Build + choose + send [item] to [chatId], returning the classified outcome. */
    suspend fun send(item: OutboxWithImages, chatId: Long): SendResult

    /** Send a fixed test message to [chatId] (used by pairing / settings "Send test"). */
    suspend fun sendTestMessage(chatId: Long, text: String): SendResult
}
