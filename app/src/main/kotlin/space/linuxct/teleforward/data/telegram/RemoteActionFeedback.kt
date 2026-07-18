package space.linuxct.teleforward.data.telegram

import space.linuxct.teleforward.service.RemoteActionResult

/**
 * Pure wording for everything the app says back about a button press.
 *
 * Separated from [RemoteActionDispatcher] so the part that decides *what the user is told* can be
 * tested without a Telegram client, a database or a live notification. The distinction that actually
 * matters — "your press was too late to answer" versus "something else went wrong" — hangs on
 * pattern-matching Telegram's own error text, which is precisely the kind of thing that rots silently
 * unless a test pins it.
 */
object RemoteActionFeedback {

    /** Shown on the button itself, as a toast or a modal. */
    fun outcomeText(result: RemoteActionResult, successText: String): String = when (result) {
        is RemoteActionResult.Success -> successText
        is RemoteActionResult.NotificationGone -> "Notification is no longer on the device"
        is RemoteActionResult.ListenerUnavailable -> "Notification access is not connected"
        is RemoteActionResult.ActionUnavailable -> "That action is no longer available"
        is RemoteActionResult.Failed -> "Failed: ${result.message}"
    }

    /** Sent as a chat message, in reply to the forward, after relaying typed text. */
    fun replyOutcomeText(result: RemoteActionResult): String = when (result) {
        is RemoteActionResult.Success -> "✅ Sent"
        is RemoteActionResult.NotificationGone -> "⚠️ That notification is gone — can't reply to it any more"
        is RemoteActionResult.ListenerUnavailable -> "⚠️ Notification access is not connected"
        is RemoteActionResult.ActionUnavailable -> "⚠️ That chat can't be replied to from here"
        is RemoteActionResult.Failed -> "⚠️ Failed: ${result.message}"
    }

    /**
     * Did Telegram reject the acknowledgement because the press had already timed out?
     *
     * Telegram's wire text is `query is too old and response timeout expired or query ID is invalid`
     * — one string covering both "too late" and "never valid". Matched on the distinctive fragment
     * rather than the whole sentence so a wording tweak on their side doesn't turn this back into the
     * silent failure it was written to expose. Case-insensitive: the real string capitalises `ID`.
     */
    fun isStale(failure: String?): Boolean {
        if (failure.isNullOrBlank()) return false
        return STALE_MARKERS.any { failure.contains(it, ignoreCase = true) }
    }

    /**
     * What to say in the chat when the button's own answer could not be delivered.
     *
     * This is the whole point of the feature: a press that lands while nothing is polling can never be
     * answered on the button — Telegram stops accepting an answer seconds after the press, long before
     * the app next looks. The chat message is the only surviving channel, so it carries the result,
     * the reason, and the one setting that prevents a recurrence. Saying merely "that failed" would
     * leave the user exactly where they started: a live notification and a dead button.
     */
    fun undeliveredAnswerText(note: String, failure: String?): String = buildString {
        if (isStale(failure)) {
            append("⏳ That press reached me too late for Telegram to show the result on the button.\n\n")
            append("Result: ").append(note).append("\n\n")
            append(
                "Why: the button was pressed while TeleForward wasn't listening. It checks Telegram " +
                    "for a few minutes after each forward, then a few more times over the next couple " +
                    "of hours — a press outside those windows waits for the next check.\n" +
                    "To make presses act immediately: Settings → Remote actions → “Always listening”.",
            )
        } else {
            append("⚠️ I couldn't show the result on the button.\n\n")
            append("Result: ").append(note).append("\n")
            append("Reason: ").append(failure ?: "unknown")
        }
    }

    private val STALE_MARKERS = listOf("too old", "query id is invalid", "timeout")
}
