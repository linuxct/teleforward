package space.linuxct.teleforward.data.link

import java.net.URLEncoder

/**
 * Odesli / song.link universal-link helper for the "now playing" magic link.
 *
 * A media notification (Spotify, YouTube Music, Deezer, Tidal, a local player like Poweramp, …) only
 * exposes the **track** title and **artist** as plain text — never a catalogue id or a readable deep
 * link (the tap action is a `PendingIntent` we can't read). Unlike Apple Music, there is no per-service
 * keyless "search → canonical url" API we can ship for each of these players (Spotify's needs OAuth).
 *
 * The trick is to resolve the (artist, track) to ONE catalogue url we *can* get keylessly — Apple
 * Music, via the iTunes Search API the app already uses — and then wrap that in an Odesli
 * **song.link** universal page. That page auto-routes each recipient into whatever service *they* use
 * (Spotify, Deezer, Tidal, Amazon Music, YouTube Music, …), so a single reconstructed link works for
 * every player, including the ones with no keyless API of their own.
 *
 * [universalUrl] is a pure string transform — `song.link/<url-encoded source url>` resolves with no API
 * call at all (Odesli 308-redirects it to the resolved universal page). Everything here is pure and
 * unit-testable; the network lookup that produces the source url lives in [LinkResolver].
 */
object SongLink {

    const val SERVICE = "songLink"

    private const val BASE = "https://song.link/"

    /**
     * Wrap a source platform url (e.g. an `music.apple.com` song url) in a song.link universal page url.
     * The source url is url-encoded as a single path segment — the raw, un-encoded form collapses
     * `https://` to `https:/` and breaks the redirect, so encoding is required, not cosmetic. Pure.
     */
    fun universalUrl(sourceUrl: String): String =
        BASE + URLEncoder.encode(sourceUrl.trim(), "UTF-8")
}
