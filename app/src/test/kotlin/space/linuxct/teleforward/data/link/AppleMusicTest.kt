package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the Apple Music magic-link's pure, network-free halves: the iTunes Search URL /
 * storefront building, the `results[]` → [AppleMusic.Track] parse, and the confidence-gated
 * [AppleMusic.pickTrack]. Runs under Robolectric so the real `org.json` is available on the JVM (the
 * mockable `android.jar` stubs it out) — mirrors [LinkResolverSearchTest].
 *
 * The fixtures are trimmed but faithful iTunes Search API responses for real tracks captured from a
 * device diagnostics dump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppleMusicTest {

    @Test
    fun searchUrlEncodesTermAndParams() {
        val url = AppleMusic.searchUrl(artist = "Daft Punk", track = "The Prime Time of Your Life", storefront = "es")
        assertEquals(
            "https://itunes.apple.com/search" +
                "?term=Daft+Punk+The+Prime+Time+of+Your+Life&entity=song&limit=6&country=es",
            url,
        )
    }

    @Test
    fun storefrontFromCountryElseDefaultUs() {
        assertEquals("es", AppleMusic.storefront("ES"))
        assertEquals("us", AppleMusic.storefront("us"))
        assertEquals("us", AppleMusic.storefront(null))
        assertEquals("us", AppleMusic.storefront(""))
        // Non-2-letter / non-alpha inputs fall back rather than producing a bogus storefront.
        assertEquals("us", AppleMusic.storefront("United"))
        assertEquals("us", AppleMusic.storefront("4"))
    }

    @Test
    fun canonicalUrlStripsUoButKeepsSongAnchor() {
        assertEquals(
            "https://music.apple.com/es/album/bliss/1492150737?i=1492151082",
            AppleMusic.canonicalUrl("https://music.apple.com/es/album/bliss/1492150737?i=1492151082&uo=4"),
        )
        // Album-only url (no ?i=): the lone uo param is removed cleanly, no dangling '?'.
        assertEquals(
            "https://music.apple.com/es/album/bliss/1492150737",
            AppleMusic.canonicalUrl("https://music.apple.com/es/album/bliss/1492150737?uo=4"),
        )
    }

    @Test
    fun parseTracksReadsResultsAndCanonicalizes() {
        val tracks = AppleMusic.parseTracks(bliss)
        assertEquals(1, tracks.size)
        assertEquals("Dani Demand", tracks[0].artist)
        assertEquals("Bliss", tracks[0].track)
        // uo stripped, song anchor kept.
        assertEquals("https://music.apple.com/es/album/bliss/1492150737?i=1492151082", tracks[0].url)
    }

    @Test
    fun parseTracksIsEmptyOnGarbage() {
        assertTrue(AppleMusic.parseTracks("not json").isEmpty())
        assertTrue(AppleMusic.parseTracks("""{"resultCount":0,"results":[]}""").isEmpty())
    }

    @Test
    fun picksExactArtistAndTitleMatch() {
        val tracks = AppleMusic.parseTracks(bliss)
        val match = AppleMusic.pickTrack(tracks, artist = "Dani Demand", track = "Bliss")
        assertEquals("https://music.apple.com/es/album/bliss/1492150737?i=1492151082", match?.url)
    }

    @Test
    fun skipsSuffixedTopHitForExactCut() {
        // #0 is "Hurricane (feat. SHIBUI) [Festival Mix]"; the notification is the plain cut (#1).
        val tracks = AppleMusic.parseTracks(hurricane)
        val match = AppleMusic.pickTrack(
            tracks,
            artist = "Blasterjaxx, Prezioso & LIZOT",
            track = "Hurricane (feat. SHIBUI)",
        )
        assertEquals("https://music.apple.com/es/album/hurricane-plain/2000000002?i=2000000012", match?.url)
    }

    @Test
    fun matchesPrimaryArtistOverlap() {
        // iTunes lists "modus & Loudar"; even a notification of just "modus" (subset) still matches,
        // because the title matches exactly and one artist string contains the other.
        val tracks = AppleMusic.parseTracks(noLimits)
        val match = AppleMusic.pickTrack(tracks, artist = "modus", track = "No Limits (feat. Loudar)")
        assertEquals("https://music.apple.com/es/album/no-limits/3000000003?i=3000000013", match?.url)
    }

    @Test
    fun rejectsWrongArtistEvenWhenTitleMatches() {
        // A same-title song by a different artist must never be linked.
        val tracks = AppleMusic.parseTracks(bliss)
        assertNull(AppleMusic.pickTrack(tracks, artist = "Some Other Artist", track = "Bliss"))
    }

    @Test
    fun noMatchWhenTitleDiffers() {
        val tracks = AppleMusic.parseTracks(bliss)
        assertNull(AppleMusic.pickTrack(tracks, artist = "Dani Demand", track = "A Different Song"))
    }

    private val bliss = """
        {"resultCount":1,"results":[
          {"artistName":"Dani Demand","trackName":"Bliss",
           "trackViewUrl":"https://music.apple.com/es/album/bliss/1492150737?i=1492151082&uo=4"}
        ]}
    """.trimIndent()

    private val hurricane = """
        {"resultCount":2,"results":[
          {"artistName":"Blasterjaxx, Prezioso & LIZOT","trackName":"Hurricane (feat. SHIBUI) [Festival Mix]",
           "trackViewUrl":"https://music.apple.com/es/album/hurricane-festival/2000000001?i=2000000011&uo=4"},
          {"artistName":"Blasterjaxx, Prezioso & LIZOT","trackName":"Hurricane (feat. SHIBUI)",
           "trackViewUrl":"https://music.apple.com/es/album/hurricane-plain/2000000002?i=2000000012&uo=4"}
        ]}
    """.trimIndent()

    private val noLimits = """
        {"resultCount":1,"results":[
          {"artistName":"modus & Loudar","trackName":"No Limits (feat. Loudar)",
           "trackViewUrl":"https://music.apple.com/es/album/no-limits/3000000003?i=3000000013&uo=4"}
        ]}
    """.trimIndent()
}
