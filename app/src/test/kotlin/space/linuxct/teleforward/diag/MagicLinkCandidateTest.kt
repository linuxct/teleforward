package space.linuxct.teleforward.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [MagicLinkCandidate] — the pure capture-session summary. Runs under Robolectric so the
 * real `org.json` is available on the JVM (the mockable `android.jar` stubs it out); mirrors the link tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MagicLinkCandidateTest {

    @Test
    fun `discord server channel surfaces snowflake and message-id signals`() {
        // Field values from the real 2026 DistroAV/Shaide device dump: shortcutId == locusId == channelId.
        val o = MagicLinkCandidate.summarize(
            packageName = "com.discord",
            alreadySupported = false,
            tag = null,
            shortcutId = "1238004201456406590",
            locusId = "1238004201456406590",
            group = "GROUP_MESSAGE_CREATE",
            isGroupConversation = true,
            conversationTitle = "DistroAV #business",
            extrasKeys = setOf("android.title", "latestMessageId"),
        )
        val signals = o.getJSONObject("signals")
        assertTrue(signals.getBoolean("snowflakeInShortcut"))
        assertTrue(signals.getBoolean("snowflakeInLocus"))
        assertFalse(signals.getBoolean("phoneJidInTag"))
        val extras = o.getJSONArray("interestingExtras")
        assertEquals(1, extras.length())
        assertEquals("latestMessageId", extras.getString(0))
        assertFalse(o.getBoolean("alreadySupported"))
        assertEquals("DistroAV #business", o.getString("conversationTitle"))
    }

    @Test
    fun `whatsapp phone jid in tag and shortcut is flagged`() {
        val o = MagicLinkCandidate.summarize(
            packageName = "com.whatsapp",
            alreadySupported = true,
            tag = "34600112233@s.whatsapp.net",
            shortcutId = "34600112233@s.whatsapp.net",
            locusId = null,
            group = null,
            isGroupConversation = false,
            conversationTitle = null,
            extrasKeys = emptySet(),
        )
        val signals = o.getJSONObject("signals")
        assertTrue(signals.getBoolean("phoneJidInTag"))
        assertTrue(signals.getBoolean("phoneJidInShortcut"))
        assertFalse(signals.getBoolean("snowflakeInTag"))
        assertFalse(signals.getBoolean("hasLocusId"))
        assertTrue(o.getBoolean("alreadySupported"))
        // No interesting extras present -> the key is omitted entirely.
        assertFalse(o.has("interestingExtras"))
    }

    @Test
    fun `pure signal helpers classify shapes correctly`() {
        assertTrue(MagicLinkCandidate.looksLikeSnowflake("1238004201456406590"))
        assertFalse("too short", MagicLinkCandidate.looksLikeSnowflake("12345"))
        assertFalse("non-digit", MagicLinkCandidate.looksLikeSnowflake("12a8004201456406590"))
        assertFalse(MagicLinkCandidate.looksLikeSnowflake(null))
        assertTrue(MagicLinkCandidate.looksLikePhoneJid("34600112233@s.whatsapp.net"))
        assertFalse("lid is not a phone jid", MagicLinkCandidate.looksLikePhoneJid("12345678@lid"))
        assertFalse(MagicLinkCandidate.looksLikePhoneJid(null))
    }
}
