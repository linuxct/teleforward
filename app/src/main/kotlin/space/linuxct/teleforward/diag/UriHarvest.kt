package space.linuxct.teleforward.diag

/**
 * §11 candidate-URI harvest: scans arbitrary captured strings (extras text, URLSpans, Person/Icon
 * URIs, locusId/shortcutId, MessagingStyle data URIs, RemoteViews texts, resolved-Intent data/toUri)
 * for URLs / URIs / `intent:` / `android-app:` / `vnd.*` / `tel:` / `mailto:` / `geo:` etc.
 *
 * Pure Kotlin (unit-tested); no Android dependencies. De-duplicates while preserving first-seen order.
 */
object UriHarvest {

    private val PATTERNS = listOf(
        // scheme://... (http, https, content, android-app, whatsapp, market, ...)
        Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>()\[\]{}\\]+"""),
        // intent: with or without //
        Regex("""intent:[^\s"'<>\\]+"""),
        // vnd.* opaque (vnd.youtube:..., vnd.android.cursor:...)
        Regex("""vnd\.[a-zA-Z0-9.+\-]*:[^\s"'<>()\[\]{}\\]+"""),
        // common opaque schemes carrying high-value payloads
        Regex("""(?:tel|mailto|sms|smsto|mms|geo|maps|market|whatsapp):[^\s"'<>()\[\]{}\\]+"""),
    )

    private const val TRIM_TAIL = ".,;:!?)]}>\"'"

    fun harvest(strings: Iterable<String?>): List<String> {
        val out = LinkedHashSet<String>()
        for (s in strings) {
            if (s.isNullOrEmpty()) continue
            for (p in PATTERNS) {
                for (m in p.findAll(s)) {
                    val cand = m.value.trimEnd { it in TRIM_TAIL }
                    if (cand.length >= 4) out += cand
                }
            }
        }
        return out.toList()
    }
}
