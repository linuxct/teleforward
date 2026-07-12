package space.linuxct.teleforward.data.telegram

/**
 * The chosen way to deliver an outbox item, computed by [MessageBuilder.plan] from the message
 * text length and image count (Telegram limits: text 4096, caption 1024, media group 2–10).
 */
sealed interface SendPlan {

    /** No images: a single sendMessage. */
    data class TextOnly(val text: String) : SendPlan

    /** One image, text fits in a caption (≤1024): a single sendPhoto with caption. */
    data class SinglePhoto(val imagePath: String, val caption: String) : SendPlan

    /** One image, text too long for a caption: sendMessage then sendPhoto. */
    data class PhotoWithSeparateText(val imagePath: String, val text: String) : SendPlan

    /**
     * 2–10 images: one sendMediaGroup. [caption] goes on the first item; if the text is too long
     * for a caption it is sent first as [precedingText] via sendMessage.
     */
    data class MediaGroup(
        val imagePaths: List<String>,
        val caption: String,
        val precedingText: String?,
    ) : SendPlan

    /** More than 10 images: batched into groups of ≤10 sendMediaGroup calls. */
    data class MediaGroupBatched(
        val batches: List<List<String>>,
        val caption: String,
        val precedingText: String?,
    ) : SendPlan
}

/**
 * Result of a send attempt, carrying the error classification needed by the delivery worker's
 * retry/terminal decision (see plan T4).
 */
sealed interface SendResult {

    /**
     * 2xx — delivered. [messageIds] are the resulting Telegram message ids.
     *
     * The `editable*` fields point at the single message that carries the body/caption (where a
     * later `Link:` line would go) so the magic-link retry can edit it: [editableMessageId] is that
     * message's id, [editableIsCaption] whether it is a caption (vs message text), and [editableText]
     * the exact caption/text that was sent WITHOUT any magic link. All three are null/false when
     * there is no clean single editable target, in which case the retry is skipped.
     */
    data class Success(
        val messageIds: List<Long>,
        val editableMessageId: Long? = null,
        val editableIsCaption: Boolean = false,
        val editableText: String? = null,
    ) : SendResult

    /** 429 — honor `parameters.retry_after`; the caller should retry after [seconds]. */
    data class RetryAfter(val seconds: Long) : SendResult

    /** Network error or 5xx — retry with backoff. */
    data class Transient(val message: String) : SendResult

    /** 400 BadRequest (e.g. bad entities) — retry once as plain text before giving up. */
    data class BadRequest(val message: String) : SendResult

    /** 403 (blocked / never pressed Start) or other terminal error — do not retry. */
    data class Terminal(val message: String) : SendResult
}

/**
 * Result of validating a bot token via getMe.
 */
sealed interface TokenValidation {
    data class Valid(val botId: Long, val botUsername: String?) : TokenValidation
    data class Invalid(val reason: String) : TokenValidation
}

/**
 * Result of the pairing auto-capture step (deleteWebhook + getUpdates).
 */
sealed interface PairingResult {
    /** A private chat's Start was captured. */
    data class Captured(val chatId: Long, val displayName: String?) : PairingResult

    /** Polling succeeded but no suitable update was found yet; caller may poll again. */
    data object NoUpdate : PairingResult

    /** An error occurred (network/API); [message] is user-presentable. */
    data class Error(val message: String) : PairingResult
}
