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

    override fun buildText(item: OutboxEntity, extraLink: String?): String =
        build(item, MessageBuilder.TEXT_LIMIT, extraLink)

    override fun buildCaption(item: OutboxEntity, extraLink: String?): String =
        build(item, MessageBuilder.CAPTION_LIMIT, extraLink)

    override fun escapeHtml(raw: String): String =
        raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    override fun appendLink(text: String, url: String, isCaption: Boolean): String {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return text

        val limit = if (isCaption) MessageBuilder.CAPTION_LIMIT else MessageBuilder.TEXT_LIMIT
        val linkLine = "$LINK_PREFIX${escapeHtml(cleanUrl)}"
        if (text.isEmpty()) return linkLine

        // Reserve the link line plus its leading newline; trim the existing text into what's left.
        val budget = limit - linkLine.length - 1 /* newline */
        if (budget <= 0) return linkLine
        val fittedText = if (text.length <= budget) text else trimToBudget(text, budget)
        return if (fittedText.isEmpty()) linkLine else "$fittedText\n$linkLine"
    }

    /**
     * Trim [text] to at most [budget] characters. Prefers to drop whole trailing lines (cut at the
     * last newline that fits) so a line's HTML markup (`<b>…</b>`) is never split. Only when the very
     * first line already exceeds [budget] does it char-trim: it then bails to "" if that line carries
     * HTML tags (to avoid emitting unbalanced markup), otherwise cuts on a safe char boundary.
     */
    private fun trimToBudget(text: String, budget: Int): String {
        val newlineCut = text.lastIndexOf('\n', budget)
        if (newlineCut >= 0) return text.substring(0, newlineCut)
        // The first line alone exceeds the budget. If it carries template markup ('<'), drop it whole.
        if (text.substring(0, budget.coerceAtMost(text.length)).contains('<')) return ""
        return safeCharCut(text, budget)
    }

    /** Cut [text] to [budget] chars without splitting a surrogate pair or an `&…;` HTML entity. */
    private fun safeCharCut(text: String, budget: Int): String {
        var end = budget.coerceIn(0, text.length)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        val amp = text.lastIndexOf('&', (end - 1).coerceAtLeast(0))
        if (amp >= 0) {
            val semi = text.indexOf(';', amp)
            if (semi == -1 || semi >= end) end = amp
        }
        return text.substring(0, end.coerceAtLeast(0))
    }

    override fun plan(item: OutboxWithImages, extraLink: String?): SendPlan {
        val outbox = item.outbox
        val paths = item.images.map { it.filePath }
        val text = buildText(outbox, extraLink)
        val caption = buildCaption(outbox, extraLink)
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
     * (header, title, body, extracted links, time, magic link); each free-text field is truncated on
     * its plain form to the remaining budget and only then escaped. The lengths of the trailing lines
     * — the Tier-0 extracted links, the time line, and (when present) the magic `Link:` line — are
     * always held in reserve, so a long body can never push a trailing line out or over the limit.
     */
    private fun build(item: OutboxEntity, limit: Int, extraLink: String?): String {
        val timeLine = formatTimeLine(item.postTime)
        val timeReserve = if (timeLine.isNotEmpty()) timeLine.length + 1 /* newline */ else 0
        val magicLine = formatLinkLine(extraLink)
        val magicReserve = if (magicLine.isNotEmpty()) magicLine.length + 1 /* newline */ else 0

        // Tier-0 harvested links (app-agnostic, always on): escape each so it matches an already-inline
        // (escaped) occurrence in the body and is safe under HTML parse mode. Skip the magic-link url,
        // which is appended separately as its own reconstructed `Link:` line.
        val magicUrl = extraLink?.trim()
        val extractedLines = parseExtractedLinks(item.extractedLinks)
            .asSequence()
            .filter { it != magicUrl }
            .map { escapeHtml(it) }
            .toList()
        var extractedReserve = extractedLines.sumOf { it.length + 1 /* newline */ }

        val sb = StringBuilder()

        // Room left for more content, always keeping the trailing reserves (extracted links + time +
        // magic link) so a long body can never push a trailing line out or over the limit.
        fun remaining(): Int = limit - sb.length - extractedReserve - timeReserve - magicReserve

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

        // Tier-0 direct links: one bare (escaped) url per line, but only those NOT already present in
        // the assembled text — deduped against the body's inline links and against each other, and
        // truncation-safe (a link the truncated body no longer contains is re-appended here). As each
        // candidate is considered its own reserve is released, so the fit check stays exact and a link
        // that still doesn't fit is dropped rather than the body.
        for (escLink in extractedLines) {
            extractedReserve -= escLink.length + 1
            if (sb.contains(escLink)) continue
            val leadingNl = if (sb.isNotEmpty()) 1 else 0
            val cost = leadingNl + escLink.length
            if (sb.length + cost + extractedReserve + timeReserve + magicReserve <= limit) {
                appendLine(escLink)
            }
        }

        // Time: <i>{time}</i>
        if (timeLine.isNotEmpty()) appendLine(timeLine)

        // Link: {url} — always the final line; its length was reserved above so it survives truncation.
        if (magicLine.isNotEmpty()) appendLine(magicLine)

        return sb.toString()
    }

    /**
     * Parse the newline-joined [OutboxEntity.extractedLinks] back into an ordered, de-duplicated list
     * of bare urls, capped at [MAX_EXTRACTED_LINKS]. Empty when the column is null/blank.
     */
    private fun parseExtractedLinks(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_EXTRACTED_LINKS)
            .toList()
    }

    private fun formatTimeLine(postTime: Long): String {
        if (postTime <= 0L) return ""
        val formatted = timeFormatter.format(Instant.ofEpochMilli(postTime))
        return "<i>${escapeHtml(formatted)}</i>"
    }

    /** Final `Link: <url>` line (HTML-escaped url; Telegram auto-links it), or "" when absent. */
    private fun formatLinkLine(extraLink: String?): String {
        if (extraLink.isNullOrBlank()) return ""
        return "$LINK_PREFIX${escapeHtml(extraLink.trim())}"
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
        const val LINK_PREFIX = "Link: "

        /** Upper bound on Tier-0 links appended to a single message (keeps the forward readable). */
        const val MAX_EXTRACTED_LINKS = 5
    }
}
