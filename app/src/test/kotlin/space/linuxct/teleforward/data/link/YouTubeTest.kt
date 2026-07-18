package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTest {

    @Test
    fun extractsChannelIdFromTag() {
        // Real-device shape: sbn.tag = "UC…::<uuid>".
        assertEquals(
            "UCg40OxZ1GYh3u3jBntB6DLg",
            YouTube.extractChannelId(
                chimeSlotKey = null,
                tag = "UCg40OxZ1GYh3u3jBntB6DLg::0f6c1c2e-1234-5678-9abc-def012345678",
            ),
        )
    }

    @Test
    fun extractsChannelIdFromChimeSlotKey() {
        // chime.slot_key is the bare channel id.
        assertEquals(
            "UCg40OxZ1GYh3u3jBntB6DLg",
            YouTube.extractChannelId(chimeSlotKey = "UCg40OxZ1GYh3u3jBntB6DLg", tag = null),
        )
    }

    @Test
    fun chimeSlotKeyIsPreferredOverTag() {
        assertEquals(
            "UCg40OxZ1GYh3u3jBntB6DLg",
            YouTube.extractChannelId(
                chimeSlotKey = "UCg40OxZ1GYh3u3jBntB6DLg",
                tag = "UCzzzzzzzzzzzzzzzzzzzzzz::abc",
            ),
        )
    }

    @Test
    fun nullWhenBothAbsent() {
        assertNull(YouTube.extractChannelId(chimeSlotKey = null, tag = null))
    }

    @Test
    fun nullForNonChannelTag() {
        // Group-summary tag and other non-UC strings must not match.
        assertNull(YouTube.extractChannelId(chimeSlotKey = null, tag = "SUMMARY::0f6c1c2e"))
        assertNull(YouTube.extractChannelId(chimeSlotKey = "not-a-channel-id", tag = null))
    }

    // --- live / premiere video ids -------------------------------------------------------------

    @Test
    fun extractsVideoIdFromSlotKey() {
        // Real device shape for a premiere: chime.slot_key IS the 11-char video id.
        assertEquals("PremiereVid", YouTube.extractVideoId(chimeSlotKey = "PremiereVid", tag = null))
        assertEquals("LiveVid_002", YouTube.extractVideoId(chimeSlotKey = "LiveVid_002", tag = null))
    }

    @Test
    fun extractsVideoIdFromTagPrefix() {
        assertEquals(
            "PremiereVid",
            YouTube.extractVideoId(
                chimeSlotKey = null,
                tag = "PremiereVid::0f6c1c2e-1234-5678-9abc-def012345678",
            ),
        )
    }

    @Test
    fun channelIdIsNeverMistakenForAVideoId() {
        // A channel id is 24 chars and contains 11-char windows — a substring search would produce
        // garbage here, so extraction must be whole-string.
        assertNull(YouTube.extractVideoId(chimeSlotKey = "UCtestChannel_0123456789", tag = null))
        assertNull(
            YouTube.extractVideoId(
                chimeSlotKey = null,
                tag = "UCtestChannel_0123456789::0f6c1c2e-1234-5678-9abc-def012345678",
            ),
        )
    }

    @Test
    fun rejectsNonVideoKeys() {
        // YouTube's numeric group-summary key (10 digits) and other junk must not become a video id.
        assertNull(YouTube.extractVideoId(chimeSlotKey = "1234567890", tag = null))
        // All-digit 11-char keys are guarded against explicitly.
        assertNull(YouTube.extractVideoId(chimeSlotKey = "12345678901", tag = null))
        assertNull(YouTube.extractVideoId(chimeSlotKey = "short", tag = null))
        assertNull(YouTube.extractVideoId(chimeSlotKey = null, tag = null))
    }

    @Test
    fun videoAndChannelExtractionDoNotCollide() {
        // An upload notification yields a channel id and NO video id...
        val uploadSlot = "UCtestChannel_0123456789"
        assertEquals(uploadSlot, YouTube.extractChannelId(uploadSlot, null))
        assertNull(YouTube.extractVideoId(uploadSlot, null))
        // ...and a premiere notification yields a video id and NO channel id.
        val liveSlot = "PremiereVid"
        assertEquals(liveSlot, YouTube.extractVideoId(liveSlot, null))
        assertNull(YouTube.extractChannelId(liveSlot, null))
    }
}
