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
    /**
     * The message this one replies to. Replying to a forwarded notification (or to a reply prompt) is
     * how a typed reply is routed back to the source app's RemoteInput action.
     */
    @SerialName("reply_to_message") val replyToMessage: TgMessage? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("channel_post") val channelPost: TgMessage? = null,
    /** Raised when the user presses an inline button under a forwarded message. */
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

/** An inline-button press. [data] is the opaque token we put in the button's `callback_data`. */
@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser? = null,
    /** The message the pressed button is attached to (absent for very old messages). */
    val message: TgMessage? = null,
    val data: String? = null,
)

/**
 * A single inline button. `callback_data` is capped by Telegram at **64 bytes**, which is why we send
 * a short opaque token and keep the real target in `callback_tokens`.
 */
@Serializable
data class TgInlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val url: String? = null,
)

/**
 * Buttons rendered **underneath one specific message** (Telegram's `inline_keyboard`) — each message
 * keeps its own set indefinitely, unlike a reply keyboard which is chat-wide.
 */
@Serializable
data class TgInlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<TgInlineKeyboardButton>>,
)

/** Asks the client to pop up the reply UI, so the user can type a reply to be relayed to the device. */
@Serializable
data class TgForceReply(
    @SerialName("force_reply") val forceReply: Boolean = true,
    val selective: Boolean = true,
    @SerialName("input_field_placeholder") val placeholder: String? = null,
)
