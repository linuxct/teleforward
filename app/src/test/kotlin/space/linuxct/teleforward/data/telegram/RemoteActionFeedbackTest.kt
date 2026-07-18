package space.linuxct.teleforward.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.linuxct.teleforward.service.RemoteActionResult

/**
 * The wording rules behind "a button press must never fail silently".
 *
 * The case that matters is a press made while nothing was polling: Telegram refuses the
 * acknowledgement, the button's toast can never appear, and the chat message becomes the only way the
 * user finds out anything happened. Getting the detection wrong turns that back into silence.
 */
class RemoteActionFeedbackTest {

    @Test
    fun recognisesTelegramsRealRejectionString() {
        // Verbatim from the Bot API server source. Note the capital "ID" — matching must not be
        // case-sensitive, or the one error this feature exists to catch slips through.
        val real = "Bad Request: query is too old and response timeout expired or query ID is invalid"
        assertTrue(RemoteActionFeedback.isStale(real))
        assertTrue(RemoteActionFeedback.isStale(real.lowercase()))
        assertTrue(RemoteActionFeedback.isStale(real.uppercase()))
    }

    @Test
    fun ordinaryFailuresAreNotTreatedAsStale() {
        // These deserve the plain "couldn't show the result" wording: advising the user about
        // "Always listening" would be a non-sequitur when the network simply dropped.
        assertFalse(RemoteActionFeedback.isStale("HTTP 500"))
        assertFalse(RemoteActionFeedback.isStale("Unauthorized"))
        assertFalse(RemoteActionFeedback.isStale("no response from Telegram"))
        assertFalse(RemoteActionFeedback.isStale(null))
        assertFalse(RemoteActionFeedback.isStale(""))
    }

    @Test
    fun staleMessageExplainsTheCauseAndTheCure() {
        val text = RemoteActionFeedback.undeliveredAnswerText(
            note = "Dismissed",
            failure = "Bad Request: query is too old and response timeout expired or query ID is invalid",
        )
        // Carries what happened...
        assertTrue(text.contains("Dismissed"))
        // ...why the button appeared to hang...
        assertTrue(text.contains("wasn't listening"))
        // ...and the setting that prevents a recurrence. Without this the user is left exactly where
        // the original bug left them: a live notification and a dead button.
        assertTrue(text.contains("Always listening"))
    }

    @Test
    fun nonStaleMessageReportsTheActualReasonInstead() {
        val text = RemoteActionFeedback.undeliveredAnswerText(note = "Dismissed", failure = "HTTP 502")
        assertTrue(text.contains("HTTP 502"))
        assertFalse(text.contains("Always listening"))
    }

    @Test
    fun everyOutcomeSaysSomethingSpecific() {
        // No outcome may fall back to a generic shrug: "what went wrong" is the whole deliverable.
        assertEquals("Dismissed", RemoteActionFeedback.outcomeText(RemoteActionResult.Success, "Dismissed"))
        assertEquals(
            "Notification is no longer on the device",
            RemoteActionFeedback.outcomeText(RemoteActionResult.NotificationGone, "Dismissed"),
        )
        assertEquals(
            "Notification access is not connected",
            RemoteActionFeedback.outcomeText(RemoteActionResult.ListenerUnavailable, "Dismissed"),
        )
        assertEquals(
            "That action is no longer available",
            RemoteActionFeedback.outcomeText(RemoteActionResult.ActionUnavailable, "Dismissed"),
        )
        assertEquals(
            "Failed: boom",
            RemoteActionFeedback.outcomeText(RemoteActionResult.Failed("boom"), "Dismissed"),
        )
    }

    @Test
    fun replyOutcomesAreDistinctFromButtonOutcomes() {
        // A relayed reply is reported in the chat, so it carries its own emoji-led wording rather than
        // reusing the terse button text.
        assertEquals("✅ Sent", RemoteActionFeedback.replyOutcomeText(RemoteActionResult.Success))
        assertTrue(
            RemoteActionFeedback.replyOutcomeText(RemoteActionResult.NotificationGone)
                .startsWith("⚠️"),
        )
    }
}
