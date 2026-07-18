package space.linuxct.teleforward.data.db.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the distinction that decides whether a message's buttons survive a press.
 *
 * A now-playing control stays useful after every press — pause, then play again — so its keyboard
 * must never be collapsed. A one-shot forwarded notification's buttons only go away once there is
 * genuinely nothing left to act on.
 */
class CallbackTokenEntityTest {

    private fun token(outboxId: Long) = CallbackTokenEntity(
        token = "t",
        kind = "FIRE",
        notificationKey = "0|com.example|1|null|10",
        actionIndex = 0,
        semantic = 0,
        label = "Pause",
        position = 0,
        chatId = 1L,
        messageId = 2L,
        outboxId = outboxId,
        expiresAt = 0L,
        createdAt = 0L,
    )

    @Test
    fun nowPlayingButtonsArePersistent() {
        assertTrue(token(CallbackTokenEntity.NO_OUTBOX).isPersistentControl)
    }

    @Test
    fun forwardedMessageButtonsAreNot() {
        assertFalse(token(outboxId = 42L).isPersistentControl)
    }
}
