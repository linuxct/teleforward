package space.linuxct.teleforward.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import space.linuxct.teleforward.data.db.dao.CallbackTokenDao
import space.linuxct.teleforward.data.db.entity.CallbackTokenEntity
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.dto.TgCallbackQuery
import space.linuxct.teleforward.data.telegram.dto.TgMessage
import space.linuxct.teleforward.diag.RemoteActionDiag
import space.linuxct.teleforward.domain.RemoteActionKind
import space.linuxct.teleforward.service.NotificationActionGateway
import space.linuxct.teleforward.service.RemoteActionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns inbound Telegram events into device actions.
 *
 * Two entry points:
 *  - [handleCallback] — an inline button was pressed under a forwarded message.
 *  - [handleMessage] — the user *replied* to a forwarded message, which relays the text into the
 *    source app's RemoteInput action.
 *
 * Every path is authorised against the paired chat first: a token alone must never be enough to drive
 * the device. Everything is best-effort and never throws into the poll loop.
 *
 * ## The feedback guarantee
 *
 * A Telegram client spins on an inline button until the bot calls `answerCallbackQuery` for that
 * press. Anything that returns without answering leaves the user staring at a spinner forever, which
 * is precisely the bug this class was hardened against — a press arrived while nothing was polling,
 * was answered far too late for Telegram to accept the answer, and the user was told nothing at all
 * while the notification sat there plainly still present.
 *
 * So every press now goes through [Ack], which guarantees three things:
 *  1. **Exactly one** answer is sent, no matter which branch ran or what threw.
 *  2. The answer's *own* outcome is inspected — previously it was fire-and-forget, so a rejected
 *     answer was indistinguishable from a delivered one.
 *  3. If the answer could not be delivered, the same information is repeated as a **chat message**,
 *     which persists. That is the only channel left once the button's toast is unreachable, and it
 *     carries the reason plus how to stop it recurring.
 */
@Singleton
class RemoteActionDispatcher @Inject constructor(
    private val api: TelegramApi,
    private val callbackTokenDao: CallbackTokenDao,
    private val gateway: NotificationActionGateway,
    private val keyboards: RemoteActionKeyboards,
    private val settings: SettingsRepository,
    private val diag: RemoteActionDiag,
) {

    /**
     * Handle an inline-button press: resolve the token, perform the action, report back.
     *
     * The body is wrapped so that no outcome — including an unexpected throw from the database, the
     * binder, or the network — can end without the user being told something.
     */
    suspend fun handleCallback(query: TgCallbackQuery) {
        val ack = Ack(query)
        try {
            dispatch(query, ack)
        } catch (ce: CancellationException) {
            // The poll worker was stopped mid-press. The press is real and the user is watching a
            // spinner, so answer under NonCancellable before unwinding — a plain call here would be
            // cancelled immediately and silently.
            withContext(NonCancellable) { ack.answer(STOPPED_TEXT, alert = true) }
            throw ce
        } catch (t: Throwable) {
            ack.answer("Couldn't complete that: ${t.message ?: t.javaClass.simpleName}", alert = true)
        } finally {
            // Runs on every path, including the cancellation one.
            withContext(NonCancellable) { ack.finish() }
        }
    }

    private suspend fun dispatch(query: TgCallbackQuery, ack: Ack) {
        val data = query.data
        if (data.isNullOrEmpty() || data == RemoteActionKeyboards.NOOP_TOKEN) {
            // A label-only button. Answer to clear the spinner; there is nothing to report.
            ack.answer(null)
            return
        }

        // Three different things used to be reported as "Not authorised", which is only true for one
        // of them. Telling someone their own press was unauthorised — when the real story is that the
        // app is no longer paired — sends them looking for a security problem that isn't there.
        val chatId = query.message?.chat?.id
        val pairedChatId = settings.chatId.first()
        if (chatId == null || pairedChatId == null || chatId != pairedChatId) {
            val reason = when {
                chatId == null -> "noChat"
                pairedChatId == null -> "notPaired"
                else -> "unauthorized"
            }
            diag.callback(
                tokenFound = false,
                authorized = false,
                kind = null,
                notificationKey = null,
                actionIndex = null,
                semantic = null,
                outcome = reason,
            )
            ack.answer(
                when (reason) {
                    "noChat" -> "Couldn't tell which chat that press came from"
                    "notPaired" -> "TeleForward isn't paired with a chat — re-pair it in Settings"
                    else -> "Not authorised"
                },
                alert = true,
            )
            return
        }
        // Only now may we speak into this chat: it is the paired one.
        ack.allowChatFallback(chatId!!)

        val token = callbackTokenDao.findByToken(data)
        if (token == null) {
            // "Expired" was previously reported for every miss, which is wrong and actively
            // misleading: the commonest cause is a keyboard that has since been rebuilt (the
            // now-playing control replaces its tokens on every track change), where the right advice
            // is "use the newest message", not "wait, it expired?" while the notification is alive.
            val messageId = query.message?.messageId
            val superseded = messageId != null &&
                callbackTokenDao.findByMessage(chatId, messageId).isNotEmpty()
            diag.callback(
                tokenFound = false,
                authorized = true,
                kind = null,
                notificationKey = null,
                actionIndex = null,
                semantic = null,
                outcome = if (superseded) "tokenSuperseded" else "tokenExpired",
            )
            ack.answer(
                if (superseded) SUPERSEDED_TEXT else EXPIRED_TEXT,
                alert = true,
            )
            return
        }

        when (RemoteActionKeyboards.kindOf(token.kind)) {
            RemoteActionKind.DISMISS -> applyAndReport(
                query,
                token,
                ack,
                onDevice { gateway.dismiss(token.notificationKey) },
                "Dismissed",
            )

            // The generic case: fire the app's own action exactly as the phone would.
            RemoteActionKind.FIRE -> applyAndReport(
                query,
                token,
                ack,
                onDevice {
                    gateway.fireAction(
                        key = token.notificationKey,
                        actionIndex = token.actionIndex,
                        semantic = token.semantic,
                        label = token.label,
                    )
                },
                token.label.trim(),
            )

            // Inline buttons can't collect free text, so open the client's reply box instead.
            RemoteActionKind.REPLY -> promptForReply(query, token, ack)

            null -> ack.answer("Unsupported action", alert = true)
        }
    }

    /**
     * Run device work with a ceiling, so a slow phone can't hold the answer past the point where
     * Telegram will still accept it.
     *
     * Honest about its limits: this bounds anything that suspends or is cancellable. A binder call
     * that blocks outright cannot be interrupted from here — those return promptly in practice, and
     * the [Ack] chat fallback covers it if one ever doesn't.
     */
    private suspend fun onDevice(block: suspend () -> RemoteActionResult): RemoteActionResult =
        try {
            withTimeoutOrNull(DEVICE_TIMEOUT_MS) { block() }
                ?: RemoteActionResult.Failed("the phone didn't respond within ${DEVICE_TIMEOUT_MS / 1000}s")
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            RemoteActionResult.Failed(t.message ?: t.javaClass.simpleName)
        }

    /**
     * Ask the user for the reply text.
     *
     * A `callback_query` answer is only a transient toast, and some clients (Telegram X) don't surface
     * it at all — so instead we post a **ForceReply** prompt. Clients respond to that by opening the
     * keyboard already targeted at the prompt, which both makes the instruction impossible to miss and
     * turns replying into a single tap. The prompt gets its own token row so the typed text routes
     * back to the same notification.
     *
     * The prompt send is bounded: the shared OkHttp client allows a 120s call, and this send happens
     * *before* the answer, so an unbounded one would guarantee the very spinner this class exists to
     * prevent.
     */
    private suspend fun promptForReply(query: TgCallbackQuery, token: CallbackTokenEntity, ack: Ack) {
        val prompt = withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
            runCatching {
                api.sendMessage(
                    chatId = token.chatId,
                    text = REPLY_PROMPT_TEXT,
                    parseMode = null,
                    replyMarkup = keyboards.forceReplyMarkup(REPLY_PROMPT_PLACEHOLDER),
                    // Quote the forwarded message so the prompt is visibly attached to it.
                    replyToMessageId = query.message?.messageId,
                )
            }.getOrNull()
        }

        val promptId = prompt?.body()?.result?.messageId
        if (promptId == null) {
            ack.answer("Reply to the forwarded message and I'll send it", alert = true)
            return
        }
        // Route a reply to the prompt back to this notification's reply action.
        runCatching { keyboards.registerReplyPrompt(token, promptId) }
        ack.answer(null)
    }

    /**
     * Handle a plain message. When it is a reply to a forwarded notification that has a reply action,
     * the text is relayed to the source app; anything else is ignored (the user may just be chatting).
     */
    suspend fun handleMessage(message: TgMessage) {
        val text = message.text?.trim()?.takeUnless { it.isEmpty() } ?: return
        val repliedTo = message.replyToMessage?.messageId ?: return
        val chatId = message.chat.id
        if (!isFromPairedChat(chatId)) return

        val token = callbackTokenDao.findByMessageAndKind(
            chatId = chatId,
            messageId = repliedTo,
            kind = RemoteActionKind.REPLY.name,
        )
        if (token == null) {
            // Distinguish "you replied to one of my messages, but that one can't relay text" from
            // "you're just chatting". Replying into the void with no acknowledgement is exactly the
            // kind of silence this class is meant to eliminate — but only speak up when the message
            // really is ours, or every stray reply would get a robotic answer.
            val ours = callbackTokenDao.findByMessage(chatId, repliedTo).isNotEmpty()
            if (ours) {
                diag.reply(
                    matched = false,
                    notificationKey = null,
                    textLength = text.length,
                    outcome = "noReplyAction",
                )
                runCatching {
                    api.sendMessage(
                        chatId = chatId,
                        text = NO_REPLY_ACTION_TEXT,
                        parseMode = null,
                        replyToMessageId = message.messageId,
                    )
                }
            }
            return
        }

        val result = onDevice { gateway.reply(token.notificationKey, token.actionIndex, text, token.label) }
        diag.reply(
            matched = true,
            notificationKey = token.notificationKey,
            textLength = text.length,
            outcome = result::class.simpleName ?: "unknown",
        )
        runCatching {
            api.sendMessage(
                chatId = chatId,
                text = replyOutcomeText(result),
                parseMode = null,
                replyToMessageId = message.messageId,
            )
        }
    }

    /** Report the outcome and, on success, replace the buttons with a completed-state label. */
    private suspend fun applyAndReport(
        query: TgCallbackQuery,
        token: CallbackTokenEntity,
        ack: Ack,
        result: RemoteActionResult,
        successText: String,
    ) {
        diag.callback(
            tokenFound = true,
            authorized = true,
            kind = token.kind,
            notificationKey = token.notificationKey,
            actionIndex = token.actionIndex,
            semantic = token.semantic,
            outcome = result::class.simpleName ?: "unknown",
        )
        // Anything other than plain success is worth a modal: a toast can go unseen entirely.
        ack.answer(outcomeText(result, successText), alert = result !is RemoteActionResult.Success)

        // Media playback controls must keep their whole menu: the notification survives every action,
        // so collapsing to one button would leave you able to pause but never resume. This covers the
        // dedicated now-playing control AND a media notification that was forwarded normally — the
        // latter is judged from the LIVE notification, so it holds however the message got here.
        val ongoingMedia = runCatching { gateway.isOngoingMedia(token.notificationKey) }.getOrDefault(false)
        if (token.isPersistentControl || ongoingMedia) return

        // A one-shot forwarded notification is done once acted on: collapse the menu to a single
        // button recording which action was chosen, so the message reads as settled.
        if (result !is RemoteActionResult.Success && result !is RemoteActionResult.NotificationGone) {
            return
        }

        val messageId = query.message?.messageId ?: return
        val label = if (result is RemoteActionResult.Success) "✓ $successText" else "✓ Already gone"
        runCatching {
            api.editMessageReplyMarkup(
                chatId = token.chatId,
                messageId = messageId,
                replyMarkup = keyboards.resolvedMarkup(label),
            )
        }
    }

    private fun outcomeText(result: RemoteActionResult, successText: String): String =
        RemoteActionFeedback.outcomeText(result, successText)

    private fun replyOutcomeText(result: RemoteActionResult): String =
        RemoteActionFeedback.replyOutcomeText(result)

    /** A token is not authorisation: the press must come from the one paired chat. */
    private suspend fun isFromPairedChat(chatId: Long?): Boolean {
        if (chatId == null) return false
        return settings.chatId.first() == chatId
    }

    /**
     * Single-use acknowledgement for one button press.
     *
     * Owns the promise that the user hears back. [answer] is idempotent so overlapping branches can
     * each try without risking a double answer (Telegram rejects the second), and [finish] closes the
     * two holes that produced the original bug: a path that answered nothing at all, and an answer
     * that Telegram refused while the code assumed it had landed.
     */
    private inner class Ack(private val query: TgCallbackQuery) {
        private var answered = false
        private var delivered = false
        private var failure: String? = null
        private var note: String? = null
        private var chatId: Long? = null

        /** Permit the chat-message fallback. Only ever called once the press is known to be ours. */
        fun allowChatFallback(id: Long) {
            chatId = id
        }

        suspend fun answer(text: String?, alert: Boolean = false) {
            if (answered) return
            answered = true
            note = text
            val response = runCatching {
                api.answerCallbackQuery(callbackQueryId = query.id, text = text, showAlert = alert)
            }.getOrNull()
            val body = response?.body()
            delivered = response?.isSuccessful == true && body?.ok == true
            failure = when {
                delivered -> null
                !body?.description.isNullOrBlank() -> body.description
                response != null -> "HTTP ${response.code()}"
                else -> "no response from Telegram"
            }
        }

        /**
         * Close out the press: answer if nothing did, and if the answer never landed, repeat it in
         * the chat where it cannot be missed.
         */
        suspend fun finish() {
            if (!answered) {
                // A branch returned without answering — a bug, but the user must not pay for it.
                answer(GENERIC_FAILURE_TEXT, alert = true)
            }
            diag.answer(delivered = delivered, error = failure)
            if (delivered) return
            val target = chatId ?: return
            val text = note ?: return
            runCatching {
                api.sendMessage(
                    chatId = target,
                    text = RemoteActionFeedback.undeliveredAnswerText(text, failure),
                    parseMode = null,
                    replyToMessageId = query.message?.messageId,
                )
            }
        }
    }

    private companion object {
        const val REPLY_PROMPT_TEXT = "✍️ Reply to this message and I'll send it from your phone."
        const val REPLY_PROMPT_PLACEHOLDER = "Type your reply…"

        /**
         * Ceilings on the two things that happen *before* the answer.
         *
         * Telegram stops accepting an answer roughly 15 seconds after the press (undocumented, but
         * that is where it measures out in practice), so everything on the critical path has to fit
         * inside that with room to spare. The reply prompt is the tighter of the two because it is a
         * network round trip on a client configured for a 120s call timeout — left unbounded it could
         * hold the answer for two minutes and manufacture the exact hang this class exists to remove.
         */
        const val DEVICE_TIMEOUT_MS = 6_000L
        const val PROMPT_TIMEOUT_MS = 5_000L

        const val EXPIRED_TEXT = "This button has expired"
        const val SUPERSEDED_TEXT = "These buttons were replaced — use the newest message"
        const val STOPPED_TEXT = "TeleForward stopped listening mid-press — try again"
        const val GENERIC_FAILURE_TEXT = "Something went wrong handling that press"
        const val NO_REPLY_ACTION_TEXT =
            "⚠️ That message has no reply action — this app doesn't accept replies from here."
    }
}
