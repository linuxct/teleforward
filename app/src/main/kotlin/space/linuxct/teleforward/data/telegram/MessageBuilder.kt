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

    /** Full HTML message body for a sendMessage call (truncated to [TEXT_LIMIT]). */
    fun buildText(item: OutboxEntity): String

    /** HTML caption for a sendPhoto/sendMediaGroup call (truncated to [CAPTION_LIMIT]). */
    fun buildCaption(item: OutboxEntity): String

    /** Escape a raw string for HTML parse mode (`&`, `<`, `>`). */
    fun escapeHtml(raw: String): String

    /** Choose the send strategy based on image count and text length. */
    fun plan(item: OutboxWithImages): SendPlan

    companion object {
        const val TEXT_LIMIT = 4096
        const val CAPTION_LIMIT = 1024
        const val MEDIA_GROUP_MAX = 10
        const val MEDIA_GROUP_MIN = 2
    }
}
