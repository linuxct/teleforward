package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxWithImages

/**
 * Builds the HTML message text/caption for an outbox item and decides how it should be sent.
 *
 * HTML parse mode; escape `&`, `<`, `>` per field. Assemble plain fields, truncate with headroom,
 * then escape (escaping after truncation avoids splitting HTML entities). Limits: [TEXT_LIMIT],
 * [CAPTION_LIMIT]. Template:
 * `<b>{app}</b> · {channel}` / `<b>{title}</b>` / `{body}` / `<i>{time}</i>`.
 */
interface MessageBuilder {

    /**
     * Full HTML message body for a sendMessage call (truncated to [TEXT_LIMIT]). When [extraLink] is
     * non-null it is appended as a final `Link: <url>` line, reserved so truncation never drops it.
     */
    fun buildText(item: OutboxEntity, extraLink: String? = null): String

    /** HTML caption for a sendPhoto/sendMediaGroup call (truncated to [CAPTION_LIMIT]). */
    fun buildCaption(item: OutboxEntity, extraLink: String? = null): String

    /** Escape a raw string for HTML parse mode (`&`, `<`, `>`). */
    fun escapeHtml(raw: String): String

    /**
     * Append a final `Link: <escaped url>` line to an already-built [text] (a caption when
     * [isCaption], else a message body), for the background edit-after-send retry. Truncation-safe:
     * the link line is reserved and [text] trimmed — dropping whole trailing lines first so a line's
     * HTML markup is never split — so the result stays within Telegram's [CAPTION_LIMIT] (1024) /
     * [TEXT_LIMIT] (4096). Pure; a blank [url] returns [text] unchanged.
     */
    fun appendLink(text: String, url: String, isCaption: Boolean): String

    /** Choose the send strategy based on image count and text length. */
    fun plan(item: OutboxWithImages, extraLink: String? = null): SendPlan

    companion object {
        const val TEXT_LIMIT = 4096
        const val CAPTION_LIMIT = 1024
        const val MEDIA_GROUP_MAX = 10
        const val MEDIA_GROUP_MIN = 2
    }
}
