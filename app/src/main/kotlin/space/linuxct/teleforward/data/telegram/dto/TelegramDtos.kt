package space.linuxct.teleforward.data.telegram.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Generic Telegram Bot API response envelope. On success `ok == true` and [result] is populated;
 * on failure `ok == false` with [errorCode]/[description] and optionally [parameters].
 */
@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
    val parameters: TgResponseParameters? = null,
)

@Serializable
data class TgResponseParameters(
    /** Seconds to wait before retrying (carried on HTTP 429). */
    @SerialName("retry_after") val retryAfter: Long? = null,
    /** Present when a group has migrated to a supergroup. */
    @SerialName("migrate_to_chat_id") val migrateToChatId: Long? = null,
)

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val date: Long = 0,
    val text: String? = null,
    val caption: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("channel_post") val channelPost: TgMessage? = null,
)
