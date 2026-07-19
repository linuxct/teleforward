package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Discord]'s pure halves: the DM/group discrimination and the snowflake-gated url
 * building. Plain JUnit — nothing here touches Android or `org.json`.
 *
 * The snowflakes below are the real ids from the captured device dump this feature was designed
 * against (a `com.discord` MessagingStyle notification).
 */
class DiscordTest {

    private val channelId = "1238004201456406590"
    private val messageId = "1479209639156519166"

    @Test
    fun `a 1-to-1 dm builds a message-deep-linked url`() {
        assertEquals(
            "https://discord.com/channels/@me/$channelId/$messageId",
            Discord.dmUrl(channelId, messageId),
        )
    }

    @Test
    fun `a dm without a usable message id still links the channel`() {
        val expected = "https://discord.com/channels/@me/$channelId"
        assertEquals(expected, Discord.dmUrl(channelId, null))
        assertEquals(expected, Discord.dmUrl(channelId, ""))
        // A non-snowflake message id is ignored rather than pasted into the url.
        assertEquals(expected, Discord.dmUrl(channelId, "not-a-snowflake"))
    }

    @Test
    fun `a non-snowflake channel id never yields a url`() {
        // A WhatsApp JID / the `t:title` conversation-id fallback must never reach a Discord url.
        assertNull(Discord.dmUrl("34600112233@s.whatsapp.net", messageId))
        assertNull(Discord.dmUrl("t:Some Chat", messageId))
        assertNull(Discord.dmUrl(null, messageId))
        assertNull(Discord.dmUrl("", messageId))
        // Whole-string match: a snowflake embedded in other text is not accepted.
        assertNull(Discord.dmUrl("channel-$channelId", messageId))
    }

    @Test
    fun `only an explicit non-group conversation counts as a dm`() {
        assertTrue(Discord.isDirectMessage(false))
        assertFalse("server channel / group dm", Discord.isDirectMessage(true))
        // Unknown must NOT be treated as a DM — otherwise a server channel could get an `@me` url.
        assertFalse("unknown is not a dm", Discord.isDirectMessage(null))
    }

    @Test
    fun `trims surrounding whitespace on both ids`() {
        assertEquals(
            "https://discord.com/channels/@me/$channelId/$messageId",
            Discord.dmUrl("  $channelId  ", "  $messageId  "),
        )
    }
}
