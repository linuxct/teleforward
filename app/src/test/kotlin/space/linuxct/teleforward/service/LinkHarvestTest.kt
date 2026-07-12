package space.linuxct.teleforward.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHarvestTest {

    // --- http(s) only ------------------------------------------------------------------------------

    @Test
    fun findsHttpAndHttpsLinksInText() {
        val out = LinkHarvest.harvest(
            listOf(
                "Read more at https://example.com/article now",
                "or http://plain.example/x",
            ),
        )
        assertTrue(out.contains("https://example.com/article"))
        assertTrue(out.contains("http://plain.example/x"))
    }

    @Test
    fun excludesContentAndTelAndAppSchemeJunk() {
        val out = LinkHarvest.harvest(
            listOf(
                "content://com.android.contacts/contacts/lookup/abc",
                "tel:+15551234567",
                "mailto:a@b.com",
                "android-app://com.example/https/host/path",
                "whatsapp://send?text=hi",
                "Alice Person@123 said hi",
            ),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun excludesNonHttpSpanUrls() {
        // A URLSpan can carry any scheme; only http(s) may be surfaced.
        val out = LinkHarvest.harvest(
            texts = listOf("open the app"),
            spanUrls = listOf("content://media/1", "tel:+1555", "https://real.example/deep"),
        )
        assertEquals(listOf("https://real.example/deep"), out)
    }

    // --- fields we don't otherwise forward ---------------------------------------------------------

    @Test
    fun pullsFromMultipleTextLinesAndMessages() {
        // Simulates InboxStyle EXTRA_TEXT_LINES + several MessagingStyle messages flattened by the
        // Android layer: a link in a non-latest entry must still be harvested.
        val out = LinkHarvest.harvest(
            listOf(
                "2 new messages from 2 chats", // summary android.text (no link)
                "Bob: check https://link.example/one", // an older message
                "Carol: and https://link.example/two", // another message
            ),
        )
        assertTrue(out.contains("https://link.example/one"))
        assertTrue(out.contains("https://link.example/two"))
    }

    @Test
    fun pullsLinkBehindTextFromUrlSpan() {
        // Link-behind-text: the visible text is "tap here" but the real url lives in the URLSpan,
        // pre-extracted into spanUrls by the Android layer.
        val out = LinkHarvest.harvest(
            texts = listOf("tap here for details"),
            spanUrls = listOf("https://hidden.example/real-target"),
        )
        assertEquals(listOf("https://hidden.example/real-target"), out)
    }

    // --- dedupe / order / caps ---------------------------------------------------------------------

    @Test
    fun dedupesPreservingFirstSeenOrder() {
        val out = LinkHarvest.harvest(
            texts = listOf(
                "first https://a.example/1 then https://b.example/2",
                "again https://a.example/1",
            ),
            spanUrls = listOf("https://b.example/2"),
        )
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), out)
    }

    @Test
    fun trimsTrailingPunctuation() {
        val out = LinkHarvest.harvest(listOf("see (https://example.com/a)."))
        assertTrue(out.contains("https://example.com/a"))
    }

    @Test
    fun capsAtMaxLinks() {
        val many = (1..20).map { "https://example.com/$it" }
        val out = LinkHarvest.harvest(many)
        assertEquals(LinkHarvest.MAX_LINKS, out.size)
        // First-seen order is preserved, so it's the first MAX_LINKS urls.
        assertEquals("https://example.com/1", out.first())
    }

    // --- negatives ---------------------------------------------------------------------------------

    @Test
    fun plainTextAndNullsYieldNothing() {
        val out = LinkHarvest.harvest(listOf(null, "", "   ", "Meeting at 10:30, no links here"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun keyValueTextIsNotALink() {
        val out = LinkHarvest.harvest(listOf("note:remember", "status:ok"))
        assertFalse(out.any { it.startsWith("note:") || it.startsWith("status:") })
    }
}
