package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Unit tests for [SongLink.universalUrl] — the pure, network-free transform that wraps a source
 * platform url in an Odesli song.link universal-page url. Plain JUnit (no Robolectric): the helper
 * touches only [java.net.URLEncoder].
 */
class SongLinkTest {

    @Test
    fun `wraps an apple music url under the song_link host`() {
        val result = SongLink.universalUrl("https://music.apple.com/us/album/get-lucky/617154241?i=617154366")
        assertTrue("prefixed with song.link host", result.startsWith("https://song.link/"))
    }

    @Test
    fun `percent-encodes the source url so the redirect is not broken`() {
        val result = SongLink.universalUrl("https://music.apple.com/us/album/get-lucky/617154241?i=617154366")
        // The whole source url must be encoded into ONE path segment. If it were left raw, `https://`
        // would collapse to `https:/` and the `?i=` query would leak — both break the Odesli redirect.
        assertTrue("scheme separator encoded", result.contains("https%3A%2F%2Fmusic.apple.com"))
        assertTrue("query separator encoded", result.contains("%3Fi%3D617154366"))
        assertFalse("source url is not left raw", result.contains("https://music.apple.com"))
    }

    @Test
    fun `trims surrounding whitespace before encoding`() {
        val expected = "https://song.link/" + URLEncoder.encode("https://music.apple.com/x", "UTF-8")
        assertEquals(expected, SongLink.universalUrl("  https://music.apple.com/x  "))
    }
}
