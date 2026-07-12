package space.linuxct.teleforward.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkHeuristicsTest {

    private val watch = "https://www.youtube.com/watch?v="

    // --- YouTube -------------------------------------------------------------------------------

    @Test
    fun youtubeWatchVParam() {
        val r = DeepLinkHeuristics.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(watch + "dQw4w9WgXcQ", r.normalized)
        assertEquals("youtube", r.heuristic)
    }

    @Test
    fun youtubeShortLink() {
        val r = DeepLinkHeuristics.normalize("https://youtu.be/dQw4w9WgXcQ?si=abc")
        assertEquals(watch + "dQw4w9WgXcQ", r.normalized)
        assertEquals("youtube", r.heuristic)
    }

    @Test
    fun youtubeVndScheme() {
        val r = DeepLinkHeuristics.normalize("vnd.youtube:dQw4w9WgXcQ")
        assertEquals(watch + "dQw4w9WgXcQ", r.normalized)
        assertEquals("youtube", r.heuristic)
    }

    @Test
    fun youtubeShortsPath() {
        val r = DeepLinkHeuristics.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        assertEquals(watch + "dQw4w9WgXcQ", r.normalized)
    }

    @Test
    fun youtubeIdExtractionAcrossForms() {
        assertEquals("abcdefghijk", DeepLinkHeuristics.extractYouTubeId("youtu.be/abcdefghijk"))
        assertEquals("abcdefghijk", DeepLinkHeuristics.extractYouTubeId("x?v=abcdefghijk&t=3"))
        assertEquals("abcdefghijk", DeepLinkHeuristics.extractYouTubeId("vnd.youtube://abcdefghijk"))
        assertNull(DeepLinkHeuristics.extractYouTubeId("no id here at all"))
    }

    // --- WhatsApp ------------------------------------------------------------------------------

    @Test
    fun whatsappOneToOneJid() {
        val r = DeepLinkHeuristics.normalize("15551234567@s.whatsapp.net")
        assertEquals("https://wa.me/15551234567", r.normalized)
        assertEquals("whatsapp", r.heuristic)
        assertEquals("com.whatsapp", r.appGuess)
    }

    @Test
    fun whatsappGroupIsNotLinkable() {
        val r = DeepLinkHeuristics.normalize("120363021234567890@g.us")
        assertNull(r.normalized)
        assertEquals("whatsapp-group", r.heuristic)
    }

    // --- intent: -------------------------------------------------------------------------------

    @Test
    fun intentUriUnwrapsToHttps() {
        val raw = "intent://example.com/page?q=1#Intent;scheme=https;package=com.example;end"
        val r = DeepLinkHeuristics.normalize(raw)
        assertEquals("https://example.com/page?q=1", r.normalized)
        assertEquals("intent", r.heuristic)
    }

    @Test
    fun intentUriWithEmbeddedUrlExtra() {
        val raw = "intent:#Intent;action=android.intent.action.VIEW;S.url=https://news.example/article-42;end"
        val r = DeepLinkHeuristics.normalize(raw)
        assertEquals("https://news.example/article-42", r.normalized)
    }

    @Test
    fun androidAppSchemeUnwraps() {
        val r = DeepLinkHeuristics.normalize("android-app://com.example/https/host.example/path")
        assertEquals("https://host.example/path", r.normalized)
    }

    // --- generic + none ------------------------------------------------------------------------

    @Test
    fun genericHttpPassesThrough() {
        val r = DeepLinkHeuristics.normalize("https://blog.example.com/post/123")
        assertEquals("https://blog.example.com/post/123", r.normalized)
        assertEquals("http", r.heuristic)
    }

    @Test
    fun nonLinkableStringIsNone() {
        val r = DeepLinkHeuristics.normalize("just some text with no link")
        assertNull(r.normalized)
        assertEquals("none", r.heuristic)
        assertEquals("none", r.confidence)
    }

    @Test
    fun telUriIsNotMisclassifiedAsHttp() {
        val r = DeepLinkHeuristics.normalize("tel:+15551234567")
        // Not a recognized deep link target for the current heuristics — must not be a false http.
        assertTrue(r.heuristic == "none")
    }
}
