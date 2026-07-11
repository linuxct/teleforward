package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the HTML message text/caption for an outbox item and decides how it should be sent.
 *
 * HTML parse mode. Each field is escaped individually (`&`, `<`, `>`) so the literal template tags
 * (`<b>`, `<i>`) survive. Truncation happens on the **plain** field text before escaping, so a
 * multi-char HTML entity (e.g. `&amp;`) is never split across the length boundary. The time line is
 * reserved so a long body never pushes it out.
 */
@Singleton
class MessageBuilderImpl @Inject constructor() : MessageBuilder {

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern(TIME_PATTERN).withZone(ZoneId.systemDefault())

    override fun buildText(item: OutboxEntity): String = build(item, MessageBuilder.TEXT_LIMIT)

    override fun buildCaption(item: OutboxEntity): String = build(item, MessageBuilder.CAPTION_LIMIT)

    override fun escapeHtml(raw: String): String =
        raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    override fun plan(item: OutboxWithImages): SendPlan {
        val outbox = item.outbox
        val paths = item.images.map { it.filePath }
        val text = buildText(outbox)
        val caption = buildCaption(outbox)
        val fitsCaption = text.length <= MessageBuilder.CAPTION_LIMIT

        return when {
            paths.isEmpty() -> SendPlan.TextOnly(text)

            paths.size == 1 ->
                if (fitsCaption) {
                    SendPlan.SinglePhoto(paths[0], caption)
                } else {
                    SendPlan.PhotoWithSeparateText(paths[0], text)
                }

            paths.size <= MessageBuilder.MEDIA_GROUP_MAX ->
                if (fitsCaption) {
                    SendPlan.MediaGroup(paths, caption = caption, precedingText = null)
                } else {
                    SendPlan.MediaGroup(paths, caption = "", precedingText = text)
                }

            else -> {
                val batches = paths.chunked(MessageBuilder.MEDIA_GROUP_MAX)
                if (fitsCaption) {
                    SendPlan.MediaGroupBatched(batches, caption = caption, precedingText = null)
                } else {
                    SendPlan.MediaGroupBatched(batches, caption = "", precedingText = text)
                }
            }
        }
    }

    /**
     * Assemble the template within [limit] characters. Lines are added in display order
     * (header, title, body, time); each field is truncated on its plain form to the remaining
     * budget and only then escaped. The time line's length is always held in reserve.
     */
    private fun build(item: OutboxEntity, limit: Int): String {
        val timeLine = formatTimeLine(item.postTime)
        val timeReserve = if (timeLine.isNotEmpty()) timeLine.length + 1 /* newline */ else 0

        val sb = StringBuilder()

        // Room left for more content, always keeping [timeReserve] for the trailing time line.
        fun remaining(): Int = limit - sb.length - timeReserve

        fun appendLine(line: String) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }

        // Header: <b>{app}</b> · {channel}
        val app = item.appLabel
        if (app.isNotBlank()) {
            val leadingNl = if (sb.isNotEmpty()) 1 else 0
            val escApp = fitPlainToEscaped(app, remaining() - leadingNl - TAG_B_LEN)
            if (escApp.isNotEmpty()) {
                var header = "<b>$escApp</b>"
                val channel = item.channelName
                if (!channel.isNullOrBlank()) {
                    val escChannel = fitPlainToEscaped(
                        channel,
                        remaining() - leadingNl - header.length - SEPARATOR.length,
                    )
                    if (escChannel.isNotEmpty()) header = "$header$SEPARATOR$escChannel"
                }
                appendLine(header)
            }
        }

        // Title: <b>{title}</b>
        val title = item.title
        if (!title.isNullOrBlank()) {
            val leadingNl = if (sb.isNotEmpty()) 1 else 0
            val escTitle = fitPlainToEscaped(title, remaining() - leadingNl - TAG_B_LEN)
            if (escTitle.isNotEmpty()) appendLine("<b>$escTitle</b>")
        }

        // Body: {body}
        val body = item.body
        if (!body.isNullOrBlank()) {
            val leadingNl = if (sb.isNotEmpty()) 1 else 0
            val escBody = fitPlainToEscaped(body, remaining() - leadingNl)
            if (escBody.isNotEmpty()) appendLine(escBody)
        }

        // Time: <i>{time}</i>
        if (timeLine.isNotEmpty()) appendLine(timeLine)

        return sb.toString()
    }

    private fun formatTimeLine(postTime: Long): String {
        if (postTime <= 0L) return ""
        val formatted = timeFormatter.format(Instant.ofEpochMilli(postTime))
        return "<i>${escapeHtml(formatted)}</i>"
    }

    /**
     * Truncate [plain] so that its escaped form fits in [budget] characters, then escape it.
     * Truncating before escaping guarantees whole HTML entities. Appends an ellipsis when it had to
     * cut, and never splits a surrogate pair. Returns "" when nothing fits.
     */
    private fun fitPlainToEscaped(plain: String, budget: Int): String {
        if (budget <= 0 || plain.isEmpty()) return ""
        val full = escapeHtml(plain)
        if (full.length <= budget) return full

        val target = budget - ELLIPSIS.length
        if (target <= 0) return ""

        // Largest plain prefix whose escaped length still fits in [target].
        var lo = 0
        var hi = plain.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (escapeHtml(plain.substring(0, mid)).length <= target) lo = mid else hi = mid - 1
        }
        if (lo > 0 && Character.isHighSurrogate(plain[lo - 1])) lo--
        if (lo == 0) return ""
        return escapeHtml(plain.substring(0, lo)) + ELLIPSIS
    }

    private companion object {
        const val TIME_PATTERN = "yyyy-MM-dd HH:mm"
        const val SEPARATOR = " · " // " · "
        const val ELLIPSIS = "…" // …
        const val TAG_B_LEN = 7 // "<b>" + "</b>"
    }
}
