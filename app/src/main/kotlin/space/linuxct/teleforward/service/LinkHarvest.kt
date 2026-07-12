package space.linuxct.teleforward.service

/**
 * Tier-0 link harvest: collects every real `http`/`https` URL that appears anywhere in a
 * notification's readable content, so a link is forwarded even when it lives in a field we do not
 * otherwise forward — InboxStyle `EXTRA_TEXT_LINES` (when `android.text` is only a summary), older
 * `MessagingStyle` messages (we forward just the latest), or link-behind-text `URLSpan`s (the
 * visible text is not the URL).
 *
 * Pure Kotlin (no Android types) so it is unit-testable. The Android layer
 * ([space.linuxct.teleforward.service.NotificationMapperImpl]) flattens the notification into plain
 * [texts] (scanned by regex) plus already-extracted [spanUrls] (`URLSpan.getURL()` values); this
 * object filters to `http`/`https` only, trims trailing punctuation, de-duplicates in first-seen
 * order, and caps the count.
 *
 * `http`/`https` ONLY — this deliberately excludes the junk the notification dump surfaced:
 * `content://` contact URIs, `tel:`/`mailto:`, `android-app:`/app schemes, `Person@…` ids.
 */
object LinkHarvest {

    /** Matches an http(s) URL embedded anywhere in a string; stops at whitespace/quotes/brackets. */
    private val HTTP_REGEX =
        Regex("""https?://[^\s"'<>()\[\]{}\\]+""", RegexOption.IGNORE_CASE)

    /** Trailing characters stripped from a match (sentence/markup punctuation, never part of a URL). */
    private const val TRIM_TAIL = ".,;:!?)]}>\"'"

    /** Upper bound on harvested links surfaced per notification (keeps the forward readable). */
    const val MAX_LINKS = 5

    /** Defensive bounds on a single URL's length (drops pathological/garbage tokens). */
    private const val MIN_URL_LENGTH = 8 // "http://x"
    private const val MAX_URL_LENGTH = 2048

    /**
     * Harvest http(s) links from [texts] (scanned by regex) and [spanUrls] (URLSpan urls already
     * pulled from Spanned CharSequences), filtered to `http`/`https`, de-duplicated in first-seen
     * order, and capped at [MAX_LINKS]. Inline (regex) links are collected first, then behind-text
     * span urls.
     */
    fun harvest(
        texts: Iterable<CharSequence?>,
        spanUrls: Iterable<String?> = emptyList(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (cs in texts) {
            val s = cs?.toString()
            if (s.isNullOrEmpty()) continue
            for (m in HTTP_REGEX.findAll(s)) {
                if (addCandidate(out, m.value)) return out.toList()
            }
        }
        for (url in spanUrls) {
            if (addCandidate(out, url)) return out.toList()
        }
        return out.toList()
    }

    /**
     * Normalize [raw] and add it to [out] when it is a well-formed http(s) URL. Returns true once the
     * set has reached [MAX_LINKS] so callers can stop early.
     */
    private fun addCandidate(out: LinkedHashSet<String>, raw: String?): Boolean {
        if (raw.isNullOrEmpty()) return false
        val cand = raw.trim().trimEnd { it in TRIM_TAIL }
        if (cand.length < MIN_URL_LENGTH || cand.length > MAX_URL_LENGTH) return false
        // Belt-and-suspenders: only http/https survive (spanUrls could carry any scheme).
        if (!cand.startsWith("http://", ignoreCase = true) &&
            !cand.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        out += cand
        return out.size >= MAX_LINKS
    }
}
