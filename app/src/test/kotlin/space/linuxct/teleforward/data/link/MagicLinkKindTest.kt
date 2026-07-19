package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [magicLinkKind] and [usesContactsResolution] — the "single source of truth" that the resolver,
 * the per-app toggle card and the Contacts affordance all key off. If these drift, the settings UI and
 * the resolvers disagree, which is how Signal shipped briefly with no way to grant Contacts.
 */
class MagicLinkKindTest {

    @Test
    fun `every supported package maps to its kind`() {
        assertEquals(MagicLinkKind.YOUTUBE, magicLinkKind("com.google.android.youtube"))
        assertEquals(MagicLinkKind.APPLE_MUSIC, magicLinkKind("com.apple.android.music"))
        assertEquals(MagicLinkKind.WHATSAPP, magicLinkKind("com.whatsapp"))
        assertEquals(MagicLinkKind.WHATSAPP, magicLinkKind("com.whatsapp.w4b"))
        assertEquals(MagicLinkKind.DISCORD, magicLinkKind("com.discord"))
        assertEquals(MagicLinkKind.TELEGRAM, magicLinkKind("org.telegram.messenger"))
        assertEquals(MagicLinkKind.GITHUB, magicLinkKind("com.github.android"))
        assertEquals(MagicLinkKind.SIGNAL, magicLinkKind("org.thoughtcrime.securesms"))
        assertEquals(MagicLinkKind.BLUESKY, magicLinkKind("xyz.blueskyweb.app"))
    }

    @Test
    fun `telegram forks are covered`() {
        // The forks carry byte-identical notification code, so they must resolve identically.
        listOf("tw.nekomimi.nekogram", "nekox.messenger", "xyz.nextalone.nagram").forEach {
            assertEquals("fork not mapped: $it", MagicLinkKind.TELEGRAM, magicLinkKind(it))
        }
    }

    @Test
    fun `an unsupported package has no kind`() {
        assertNull(magicLinkKind("com.spotify.music"))
        assertNull(magicLinkKind("com.example.unknown"))
        assertNull(magicLinkKind(""))
    }

    @Test
    fun `every kind is reachable from some package`() {
        // Guards against adding a MagicLinkKind but forgetting to route a package to it — the resolver
        // branch would then be dead code and the toggle card would never appear.
        val reachable = MagicLinkKind.entries.filter { kind ->
            listOf(
                "com.google.android.youtube", "com.apple.android.music", "com.whatsapp",
                "com.discord", "org.telegram.messenger", "com.github.android",
                "org.thoughtcrime.securesms", "xyz.blueskyweb.app",
            ).any { magicLinkKind(it) == kind }
        }
        assertEquals(MagicLinkKind.entries.toSet(), reachable.toSet())
    }

    @Test
    fun `only youtube is worth retrying`() {
        // YouTube's feed/search lag behind a fresh upload, so a second attempt genuinely succeeds.
        assertTrue(supportsLinkRetry(MagicLinkKind.YOUTUBE))
        // For everyone else a miss is a settled answer — retrying would burn battery for nothing.
        listOf(
            MagicLinkKind.APPLE_MUSIC, MagicLinkKind.WHATSAPP, MagicLinkKind.DISCORD,
            MagicLinkKind.TELEGRAM, MagicLinkKind.GITHUB, MagicLinkKind.SIGNAL, MagicLinkKind.BLUESKY,
        ).forEach { assertFalse("$it must not be retried", supportsLinkRetry(it)) }
        assertFalse(supportsLinkRetry(null))
    }

    @Test
    fun `only the phone-number services need contacts resolution`() {
        // These two hide the peer's number behind an internal id, so the Contacts affordance must show.
        assertTrue(usesContactsResolution(MagicLinkKind.WHATSAPP))
        assertTrue(usesContactsResolution(MagicLinkKind.SIGNAL))
        // Everything else must NOT ask for Contacts.
        listOf(
            MagicLinkKind.YOUTUBE, MagicLinkKind.APPLE_MUSIC, MagicLinkKind.DISCORD,
            MagicLinkKind.TELEGRAM, MagicLinkKind.GITHUB, MagicLinkKind.BLUESKY,
        ).forEach { assertFalse("$it must not request contacts", usesContactsResolution(it)) }
        assertFalse(usesContactsResolution(null))
    }
}
