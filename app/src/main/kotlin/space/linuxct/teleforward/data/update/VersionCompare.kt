package space.linuxct.teleforward.data.update

/**
 * Pure semantic-version comparison for the update check.
 *
 * [isNewer] returns `true` iff [latestTag] represents a strictly newer version than [current]. A
 * leading `v`/`V` is stripped, an optional pre-release / build suffix (`-rc1`, `+build.5`) is
 * ignored, and the remaining dotted numeric segments (`x.y.z`, any count) are compared
 * left-to-right with missing trailing segments treated as `0` (so `1.2` == `1.2.0`). Anything that
 * doesn't parse as a dotted numeric version yields `false` — a malformed tag never counts as newer.
 */
fun isNewer(latestTag: String, current: String): Boolean {
    val latest = parseVersion(latestTag) ?: return false
    val cur = parseVersion(current) ?: return false
    return compareVersions(latest, cur) > 0
}

/** Parse a version string into its numeric segments, or `null` if it isn't a valid dotted number. */
private fun parseVersion(raw: String): List<Int>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    // Strip a single leading v/V.
    val noPrefix = if (trimmed[0] == 'v' || trimmed[0] == 'V') trimmed.substring(1) else trimmed
    // Drop a pre-release ("-rc1") or build ("+sha") suffix before parsing the numeric core.
    val core = noPrefix.substringBefore('-').substringBefore('+').trim()
    if (core.isEmpty()) return null
    val segments = core.split('.')
    val numbers = ArrayList<Int>(segments.size)
    for (segment in segments) {
        val value = segment.trim().toIntOrNull() ?: return null
        if (value < 0) return null
        numbers.add(value)
    }
    return numbers
}

/** Compare two segment lists left-to-right; shorter lists are zero-padded. */
private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    val size = maxOf(a.size, b.size)
    for (i in 0 until size) {
        val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** Normalize a tag for display / dedupe: strip a single leading `v`/`V`. */
internal fun normalizeVersion(tag: String): String {
    val trimmed = tag.trim()
    return if (trimmed.isNotEmpty() && (trimmed[0] == 'v' || trimmed[0] == 'V')) {
        trimmed.substring(1)
    } else {
        trimmed
    }
}
