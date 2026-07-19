package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [Telegram]'s pure halves: parsing the Wear `dismissalId` into a `t.me/c/` message url,
 * and refusing every non-group shape. Plain JUnit — no Android, no network.
 *
 * Ids below are synthetic placeholders shaped like real Telegram dialog / message ids.
 */
class TelegramTest {

    @Test
    fun `a group dismissal id becomes a t_me c message url`() {
        assertEquals(
            "https://t.me/c/1234567890/4567",
            Telegram.messageUrl("tgchat1234567890_4567"),
        )
    }

    @Test
    fun `a private chat is never linked`() {
        // Telegram has no shareable per-message link for a 1:1 chat, and no t.me form by numeric user id.
        assertNull(Telegram.messageUrl("tguser987654321_42"))
    }

    @Test
    fun `a secret chat is never linked`() {
        assertNull(Telegram.messageUrl("tgenc55555_7"))
    }

    @Test
    fun `malformed or missing ids yield nothing`() {
        assertNull(Telegram.messageUrl(null))
        assertNull(Telegram.messageUrl(""))
        assertNull("no separator", Telegram.messageUrl("tgchat1234567890"))
        assertNull("empty channel id", Telegram.messageUrl("tgchat_4567"))
        assertNull("empty message id", Telegram.messageUrl("tgchat1234567890_"))
        assertNull("non-numeric channel", Telegram.messageUrl("tgchatABC_4567"))
        assertNull("non-numeric message", Telegram.messageUrl("tgchat1234567890_abc"))
        assertNull("unrelated string", Telegram.messageUrl("something-else"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(
            "https://t.me/c/1234567890/4567",
            Telegram.messageUrl("  tgchat1234567890_4567  "),
        )
    }

    @Test
    fun `peer kind is classified without leaking the id`() {
        assertEquals("chat", Telegram.peerKind("tgchat1234567890_4567"))
        assertEquals("user", Telegram.peerKind("tguser987654321_42"))
        assertEquals("encrypted", Telegram.peerKind("tgenc55555_7"))
        assertEquals("none", Telegram.peerKind(null))
        assertEquals("none", Telegram.peerKind("   "))
        assertEquals("unknown", Telegram.peerKind("mystery"))
    }
}
