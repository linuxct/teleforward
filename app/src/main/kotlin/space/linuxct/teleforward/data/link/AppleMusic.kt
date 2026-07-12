package space.linuxct.teleforward.data.link

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Apple Music constants and the pure query/parse/match helpers used by the "magic link"
 * reconstruction feature.
 *
 * An Apple Music "now playing" notification is a MediaStyle notification that carries the **track**
 * title (`EXTRA_TITLE`) and the **artist** (`EXTRA_TEXT`) as plain text, but no catalog id and no
 * readable deep link (the contentIntent is blocked like everywhere else). Apple's public, no-auth
 * **iTunes Search API** (`https://itunes.apple.com/search`) maps an `artist track` term to the
 * canonical `music.apple.com` song url (`results[i].trackViewUrl`), so from (artist, track) we can
 * best-effort reconstruct the song link — the direct analogue of the YouTube (channel, title) → feed
 * reconstruction. All matching is confidence-gated (see [pickTrack]) so a wrong link is never emitted.
 */
object AppleMusic {

    /** Known Apple Music app packages that post now-playing notifications. */
    val PACKAGES: Set<String> = setOf(
        "com.apple.android.music",
    )

    /** A candidate song parsed from an iTunes Search API response. */
    data class Track(val artist: String, val track: String, val url: String)

    /**
     * The iTunes Search API URL for the `artist track` [term][searchTerm] in [storefront], restricted
     * to songs. `limit` is small but > 1 so a suffixed top hit (e.g. a "[Festival Mix]") doesn't hide
     * the exact cut further down. Pure/unit-testable.
     */
    fun searchUrl(artist: String, track: String, storefront: String): String {
        val term = URLEncoder.encode(searchTerm(artist, track), "UTF-8")
        return "$SEARCH_BASE?term=$term&entity=song&limit=$RESULT_LIMIT&country=$storefront"
    }

    /** The raw `artist track` query term (trimmed, single-spaced). Pure. */
    fun searchTerm(artist: String, track: String): String = "${artist.trim()} ${track.trim()}".trim()

    /**
     * A valid iTunes storefront: the device's 2-letter ISO country (lowercased), else [DEFAULT_STOREFRONT].
     * Keeping to the user's own storefront means the reconstructed link points at a catalog entry they
     * can actually open. Pure/unit-testable.
     */
    fun storefront(country: String?): String =
        country?.trim()?.lowercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
            ?: DEFAULT_STOREFRONT

    /**
     * Pure: parse an iTunes Search API JSON body into its song candidates (`results[]` →
     * `artistName` / `trackName` / canonicalized `trackViewUrl`). Entries without a usable track name
     * or view url are skipped. Any parse failure yields an empty list (never throws).
     */
    fun parseTracks(json: String): List<Track> = try {
        val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
        val out = ArrayList<Track>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val url = o.optString("trackViewUrl").takeUnless { it.isBlank() } ?: continue
            val track = o.optString("trackName").takeUnless { it.isBlank() } ?: continue
            out += Track(artist = o.optString("artistName"), track = track, url = canonicalUrl(url))
        }
        out
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Pure: from parsed [tracks], pick the one whose title matches [track] AND whose artist matches
     * [artist], preferring an exact normalized artist match and falling back to a primary-artist
     * overlap (one normalized artist string contains the other) — still requiring an exact normalized
     * TITLE match throughout, so a wrong-song link is never returned. Null when nothing matches.
     */
    fun pickTrack(tracks: List<Track>, artist: String, track: String): Track? {
        val wantTrack = normalize(track)
        val wantArtist = normalize(artist)
        if (wantTrack.isEmpty() || wantArtist.isEmpty()) return null
        val titleMatches = tracks.filter { normalize(it.track) == wantTrack }
        // Tier 1: exact normalized artist match.
        titleMatches.firstOrNull { normalize(it.artist) == wantArtist }?.let { return it }
        // Tier 2: primary-artist overlap (absorbs "modus" vs "modus & Loudar"), title still exact.
        return titleMatches.firstOrNull {
            val a = normalize(it.artist)
            a.isNotEmpty() && (wantArtist.contains(a) || a.contains(wantArtist))
        }
    }

    /**
     * Drop the analytics `uo=` query param from a `trackViewUrl` while keeping the song anchor
     * (`?i=<trackId>`). Pure/unit-testable.
     */
    fun canonicalUrl(url: String): String =
        url.replace(Regex("[?&]uo=\\d+"), "").let { if (it.endsWith("?")) it.dropLast(1) else it }

    /** Lowercase, alphanumerics only — mirrors the YouTube title normalization. */
    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private const val SEARCH_BASE = "https://itunes.apple.com/search"

    /** A handful of results so a suffixed top hit doesn't hide the exact cut (see [searchUrl]). */
    private const val RESULT_LIMIT = 6

    /** Storefront fallback when the device country is unknown/invalid. */
    private const val DEFAULT_STOREFRONT = "us"

    const val SERVICE = "appleMusic"
}
