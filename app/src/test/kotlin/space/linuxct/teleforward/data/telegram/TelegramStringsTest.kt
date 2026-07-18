package space.linuxct.teleforward.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.service.RemoteActionResult

/**
 * The wording rules behind "a button press must never fail silently", now read from resources.
 *
 * Robolectric rather than a plain JVM test because these strings are translated — the point is that
 * they come from `strings.xml`, and a test that hardcoded them would be asserting nothing.
 * [TelegramStrings.isStale] stays a pure companion function and is tested as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelegramStringsTest {

    private lateinit var strings: TelegramStrings

    @Before
    fun setUp() {
        strings = TelegramStrings(RuntimeEnvironment.getApplication())
    }

    @Test
    fun recognisesTelegramsRealRejectionString() {
        // Verbatim from the Bot API server source. Note the capital "ID" — matching must not be
        // case-sensitive, or the one error this feature exists to catch slips through.
        val real = "Bad Request: query is too old and response timeout expired or query ID is invalid"
        assertTrue(TelegramStrings.isStale(real))
        assertTrue(TelegramStrings.isStale(real.lowercase()))
        assertTrue(TelegramStrings.isStale(real.uppercase()))
    }

    @Test
    fun ordinaryFailuresAreNotTreatedAsStale() {
        // These deserve the plain "couldn't show the result" wording: advising the user about
        // "Always listening" would be a non-sequitur when the network simply dropped.
        assertFalse(TelegramStrings.isStale("HTTP 500"))
        assertFalse(TelegramStrings.isStale("Unauthorized"))
        assertFalse(TelegramStrings.isStale("no response from Telegram"))
        assertFalse(TelegramStrings.isStale(null))
        assertFalse(TelegramStrings.isStale(""))
    }

    @Test
    fun staleMessageExplainsTheCauseAndTheCure() {
        val text = strings.undeliveredAnswerText(
            note = "Dismissed",
            failure = "Bad Request: query is too old and response timeout expired or query ID is invalid",
        )
        // Carries what happened, why the button appeared to hang, and how to stop it recurring.
        assertTrue(text.contains("Dismissed"))
        assertTrue(text.contains("wasn't listening"))
        assertTrue(text.contains("Always listening"))
    }

    @Test
    fun nonStaleMessageReportsTheActualReasonInstead() {
        val text = strings.undeliveredAnswerText(note = "Dismissed", failure = "HTTP 502")
        assertTrue(text.contains("HTTP 502"))
        assertFalse(text.contains("Always listening"))
    }

    @Test
    fun everyOutcomeSaysSomethingSpecific() {
        // No outcome may fall back to a generic shrug: "what went wrong" is the whole deliverable.
        assertEquals("Dismissed", strings.outcomeText(RemoteActionResult.Success, "Dismissed"))
        assertEquals(
            "Notification is no longer on the device",
            strings.outcomeText(RemoteActionResult.NotificationGone, "Dismissed"),
        )
        assertEquals(
            "Notification access is not connected",
            strings.outcomeText(RemoteActionResult.ListenerUnavailable, "Dismissed"),
        )
        assertEquals(
            "That action is no longer available",
            strings.outcomeText(RemoteActionResult.ActionUnavailable, "Dismissed"),
        )
        assertEquals("Failed: boom", strings.outcomeText(RemoteActionResult.Failed("boom"), "Dismissed"))
    }

    @Test
    fun replyOutcomesAreDistinctFromButtonOutcomes() {
        assertEquals("✅ Sent", strings.replyOutcomeText(RemoteActionResult.Success))
        assertTrue(strings.replyOutcomeText(RemoteActionResult.NotificationGone).startsWith("⚠️"))
    }

    @Test
    fun buttonLabelsComeFromResources() {
        // The four labels this app owns. Everything else on a keyboard is the source app's own wording.
        val labels = strings.buttonLabels
        assertEquals("🗑 Dismiss", labels.dismiss)
        assertEquals("⏯ Play/Pause", labels.playPause)
        assertEquals("Reply", labels.replyFallback)
        assertEquals("Action", labels.actionFallback)
    }
}
