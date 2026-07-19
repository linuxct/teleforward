package space.linuxct.teleforward.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the now-playing song-link decision, and above all the **order** of its checks — the ordering
 * was a real bug: with the cache consulted first, turning the link off mid-song kept serving the cached
 * line on every play/pause edit until the track changed.
 */
class MediaLinkActionTest {

    private fun action(
        songLinkEnabled: Boolean = true,
        packageOptedOut: Boolean = false,
        sameTrackAsRendered: Boolean = false,
        track: String = "Get Lucky",
        artist: String = "Daft Punk",
    ) = mediaLinkAction(songLinkEnabled, packageOptedOut, sameTrackAsRendered, track, artist)

    @Test
    fun `a new track with usable text resolves`() {
        assertEquals(MediaLinkAction.RESOLVE, action())
    }

    @Test
    fun `the same track reuses the cached link instead of looking up again`() {
        assertEquals(MediaLinkAction.REUSE_CACHED, action(sameTrackAsRendered = true))
    }

    @Test
    fun `the global song-link switch wins over the cache`() {
        // The regression guard: OFF must take effect on the very next play/pause edit of the SAME
        // track, not only once the song changes.
        assertEquals(
            MediaLinkAction.SKIP_DISABLED,
            action(songLinkEnabled = false, sameTrackAsRendered = true),
        )
    }

    @Test
    fun `the per-app magic-link opt-out also wins over the cache`() {
        assertEquals(
            MediaLinkAction.SKIP_DISABLED,
            action(packageOptedOut = true, sameTrackAsRendered = true),
        )
    }

    @Test
    fun `either opt-out alone is enough to skip`() {
        assertEquals(MediaLinkAction.SKIP_DISABLED, action(songLinkEnabled = false))
        assertEquals(MediaLinkAction.SKIP_DISABLED, action(packageOptedOut = true))
    }

    @Test
    fun `blank track or artist skips the lookup`() {
        assertEquals(MediaLinkAction.SKIP_BLANK, action(track = ""))
        assertEquals(MediaLinkAction.SKIP_BLANK, action(artist = ""))
        assertEquals(MediaLinkAction.SKIP_BLANK, action(track = "   ", artist = "   "))
    }

    @Test
    fun `a cached same-track hit beats the blank check`() {
        // Ordering detail: if the text went blank but the track key is unchanged, keep showing what is
        // already on the card rather than dropping the link.
        assertEquals(
            MediaLinkAction.REUSE_CACHED,
            action(sameTrackAsRendered = true, track = "", artist = ""),
        )
    }
}
