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
}
