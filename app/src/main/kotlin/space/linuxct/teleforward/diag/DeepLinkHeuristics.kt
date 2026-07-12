package space.linuxct.teleforward.diag

/**
 * Normalization result for a single candidate URI/string (§12 of the plan).
 *
 * @property raw the candidate as harvested.
 * @property normalized the canonical URL, or null when the candidate is not linkable.
 * @property heuristic which rule fired: "youtube" | "whatsapp" | "whatsapp-group" | "intent" |
 *   "http" | "none".
 * @property appGuess best-effort target package, when known.
 * @property confidence "high" | "medium" | "low" | "none".
 */
data class NormalizedLink(
    val raw: String,
    val normalized: String?,
    val heuristic: String,
    val appGuess: String?,
    val confidence: String,
)

/**
 * Pure-Kotlin deep-link normalizers (§12). No Android dependencies, so it is directly unit-testable.
 *
 * Forward-compatible with the future per-app extractor:
 *  - YouTube ids (`v=`, `youtu.be/<id>`, `vnd.youtube:<id>`, shorts/embed/live) → the desktop
 *    canonical `https://www.youtube.com/watch?v=<id>`.
 *  - WhatsApp 1:1 JID `<phone>@s.whatsapp.net` → `https://wa.me/<phone>`; `@g.us` groups are flagged
 *    non-linkable.
 *  - `intent:` / `android-app:` → best-effort unwrap to a browsable URL (a pure re-implementation of
 *    the common shapes; the full `Intent.parseUri` dump happens in the Android probes).
 *  - generic `http(s)://` → pass through.
 */
object DeepLinkHeuristics {

    private val YT_SHORT = Regex("""youtu\.be/([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val YT_PATH = Regex("""youtube\.com/(?:shorts|embed|v|live)/([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val YT_V = Regex("""[?&]v=([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")
    private val YT_VND = Regex("""vnd\.youtube:/*([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""")

    private val WA_JID = Regex("""(\d{5,15})@s\.whatsapp\.net""")
    private val WA_GROUP = Regex("""[\w.-]+@g\.us""")

    /** Extract an 11-char YouTube video id from any of the recognized forms, else null. */
    fun extractYouTubeId(s: String): String? {
        YT_SHORT.find(s)?.let { return it.groupValues[1] }
        YT_PATH.find(s)?.let { return it.groupValues[1] }
        YT_V.find(s)?.let { return it.groupValues[1] }
        YT_VND.find(s)?.let { return it.groupValues[1] }
        return null
    }

    /** Canonical desktop watch URL for a video [id]. */
    fun youTubeWatchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"

    fun normalize(raw: String): NormalizedLink = normalizeInternal(raw, depth = 0)

    private fun normalizeInternal(raw: String, depth: Int): NormalizedLink {
        val s = raw.trim()

        // 1. YouTube (highest value; also catches ids embedded in http/intent URLs).
        extractYouTubeId(s)?.let { id ->
            return NormalizedLink(raw, youTubeWatchUrl(id), "youtube", "com.google.android.youtube", "high")
        }

        // 2. WhatsApp.
        WA_JID.find(s)?.let { m ->
            return NormalizedLink(raw, "https://wa.me/${m.groupValues[1]}", "whatsapp", "com.whatsapp", "medium")
        }
        if (WA_GROUP.containsMatchIn(s)) {
            return NormalizedLink(raw, null, "whatsapp-group", "com.whatsapp", "none")
        }

        // 3. intent: / android-app: → unwrap to a browsable URL, then re-normalize.
        if (s.startsWith("intent:") || s.startsWith("android-app:")) {
            val inner = unwrapIntentUri(s)
            if (inner != null && inner != s && depth < 3) {
                val nested = normalizeInternal(inner, depth + 1)
                return NormalizedLink(raw, nested.normalized ?: inner, "intent", nested.appGuess, "low")
            }
            return NormalizedLink(raw, inner, "intent", null, if (inner != null) "low" else "none")
        }

        // 4. generic http(s):// passthrough.
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return NormalizedLink(raw, s, "http", null, "medium")
        }

        return NormalizedLink(raw, null, "none", null, "none")
    }

    /**
     * Pure best-effort unwrap of an `intent:` / `android-app:` URI into a browsable URL WITHOUT
     * `android.content.Intent`. Handles the common shapes:
     *  - `intent://host/path?query#Intent;scheme=https;package=...;S.url=...;end`
     *  - `android-app://<pkg>/<scheme>/<host>/<path>` (per `Intent.URI_ANDROID_APP_SCHEME`).
     * Returns null when nothing browsable can be recovered.
     */
    fun unwrapIntentUri(s: String): String? {
        if (s.startsWith("android-app://")) {
            val parts = s.removePrefix("android-app://").split('/')
            if (parts.size >= 2 && parts[1].isNotBlank()) {
                val scheme = parts[1]
                val hostAndPath = parts.drop(2).joinToString("/")
                if (hostAndPath.isNotBlank()) return "$scheme://$hostAndPath"
            }
            return null
        }

        val hashIdx = s.indexOf("#Intent;")
        val base = if (hashIdx >= 0) s.substring(0, hashIdx) else s
        val fragment = if (hashIdx >= 0) s.substring(hashIdx + "#Intent;".length) else ""

        var scheme: String? = null
        var embeddedUrl: String? = null
        for (part in fragment.split(';')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val k = part.substring(0, eq)
            val v = part.substring(eq + 1)
            when {
                k == "scheme" -> scheme = v
                k.startsWith("S.") && (v.startsWith("http://") || v.startsWith("https://")) -> embeddedUrl = v
            }
        }
        embeddedUrl?.let { return it }

        val b = base.removePrefix("intent:").trim()
        return when {
            b.startsWith("//") && scheme != null -> "$scheme:$b"
            b.startsWith("http://") || b.startsWith("https://") -> b
            scheme != null && b.isNotBlank() && !b.contains("://") -> "$scheme://$b"
            else -> null
        }
    }
}
