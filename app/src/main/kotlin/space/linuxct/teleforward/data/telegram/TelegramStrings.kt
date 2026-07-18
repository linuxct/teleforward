package space.linuxct.teleforward.data.telegram

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import space.linuxct.teleforward.R
import space.linuxct.teleforward.domain.ButtonLabels
import space.linuxct.teleforward.service.RemoteActionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every word this app sends **into the Telegram chat**, resolved from string resources.
 *
 * The chat is a user-facing surface just as much as the app's own screens are, so its wording is
 * translated too — a Spanish phone should not be answering "Notification is no longer on the device".
 *
 * It exists as one injected class rather than scattered constants because the code that produces this
 * text is deliberately Android-free: `remoteButtons` is a pure domain function with unit tests, and
 * handing it a [ButtonLabels] keeps it that way while still letting the labels come from resources.
 * Everything else takes the strings from here directly.
 */
@Singleton
class TelegramStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Wording for the buttons this app adds itself; an app's own action titles are never rewritten. */
    val buttonLabels: ButtonLabels
        get() = ButtonLabels(
            dismiss = context.getString(R.string.tg_button_dismiss),
            playPause = context.getString(R.string.tg_button_play_pause),
            replyFallback = context.getString(R.string.tg_button_reply_fallback),
            actionFallback = context.getString(R.string.tg_button_action_fallback),
        )

    val replyPrompt: String get() = context.getString(R.string.tg_reply_prompt)
    val replyPromptPlaceholder: String get() = context.getString(R.string.tg_reply_prompt_placeholder)
    val replyFallbackHint: String get() = context.getString(R.string.tg_reply_fallback_hint)

    val buttonExpired: String get() = context.getString(R.string.tg_button_expired)
    val buttonSuperseded: String get() = context.getString(R.string.tg_button_superseded)
    val stoppedMidPress: String get() = context.getString(R.string.tg_stopped_mid_press)
    val genericFailure: String get() = context.getString(R.string.tg_generic_failure)
    val unsupportedAction: String get() = context.getString(R.string.tg_unsupported_action)
    val noReplyAction: String get() = context.getString(R.string.tg_no_reply_action)

    val notAuthorised: String get() = context.getString(R.string.tg_not_authorised)
    val noChat: String get() = context.getString(R.string.tg_no_chat)
    val notPaired: String get() = context.getString(R.string.tg_not_paired)

    val dismissed: String get() = context.getString(R.string.tg_dismissed)
    val alreadyGone: String get() = context.getString(R.string.tg_resolved_already_gone)
    val playbackEnded: String get() = context.getString(R.string.tg_playback_ended)
    val linkPrefix: String get() = context.getString(R.string.tg_link_prefix)
    val noRecipientPaired: String get() = context.getString(R.string.tg_no_recipient_paired)

    fun couldntComplete(reason: String): String =
        context.getString(R.string.tg_couldnt_complete, reason)

    fun deviceTimeout(seconds: Int): String =
        context.getString(R.string.tg_device_timeout, seconds)

    /** Shown on the button itself, as a toast or a modal. */
    fun outcomeText(result: RemoteActionResult, successText: String): String = when (result) {
        is RemoteActionResult.Success -> successText
        is RemoteActionResult.NotificationGone ->
            context.getString(R.string.tg_outcome_notification_gone)
        is RemoteActionResult.ListenerUnavailable ->
            context.getString(R.string.tg_outcome_listener_unavailable)
        is RemoteActionResult.ActionUnavailable ->
            context.getString(R.string.tg_outcome_action_unavailable)
        is RemoteActionResult.Failed ->
            context.getString(R.string.tg_outcome_failed, result.message)
    }

    /** Sent as a chat message, in reply to the forward, after relaying typed text. */
    fun replyOutcomeText(result: RemoteActionResult): String = when (result) {
        is RemoteActionResult.Success -> context.getString(R.string.tg_reply_sent)
        is RemoteActionResult.NotificationGone -> context.getString(R.string.tg_reply_gone)
        is RemoteActionResult.ListenerUnavailable ->
            context.getString(R.string.tg_reply_listener_unavailable)
        is RemoteActionResult.ActionUnavailable -> context.getString(R.string.tg_reply_unavailable)
        is RemoteActionResult.Failed -> context.getString(R.string.tg_reply_failed, result.message)
    }

    /**
     * What to say in the chat when the button's own answer could not be delivered.
     *
     * This is the whole point of the feature: a press that lands while nothing is polling can never be
     * answered on the button — Telegram stops accepting an answer seconds after the press, long before
     * the app next looks. The chat message is the only surviving channel, so it carries the result,
     * the reason, and the one setting that prevents a recurrence.
     */
    fun undeliveredAnswerText(note: String, failure: String?): String =
        if (isStale(failure)) {
            context.getString(R.string.tg_late_press_message, note)
        } else {
            context.getString(
                R.string.tg_undelivered_message,
                note,
                failure ?: context.getString(R.string.tg_reason_unknown),
            )
        }

    companion object {
        /**
         * Did Telegram reject the acknowledgement because the press had already timed out?
         *
         * Telegram's wire text is `query is too old and response timeout expired or query ID is
         * invalid` — one string covering both "too late" and "never valid". Matched on the distinctive
         * fragment rather than the whole sentence so a wording tweak on their side doesn't turn this
         * back into the silent failure it was written to expose. Case-insensitive: the real string
         * capitalises `ID`.
         *
         * Deliberately left pure and English-only: it matches TELEGRAM'S error text, which is never
         * localised, so translating it would break the detection outright.
         */
        fun isStale(failure: String?): Boolean {
            if (failure.isNullOrBlank()) return false
            return STALE_MARKERS.any { failure.contains(it, ignoreCase = true) }
        }

        private val STALE_MARKERS = listOf("too old", "query id is invalid", "timeout")
    }
}
