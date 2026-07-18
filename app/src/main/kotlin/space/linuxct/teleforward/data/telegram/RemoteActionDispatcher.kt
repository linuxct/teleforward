package space.linuxct.teleforward.data.telegram

import kotlinx.coroutines.flow.first
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

    /** Handle an inline-button press: resolve the token, perform the action, report back. */
    suspend fun handleCallback(query: TgCallbackQuery) {
        val data = query.data
        // Always answer, otherwise the client spins on the button for ~30s.
        if (data.isNullOrEmpty() || data == RemoteActionKeyboards.NOOP_TOKEN) {
            answer(query, null)
            return
        }
        if (!isFromPairedChat(query.message?.chat?.id)) {
            diag.callback(
                tokenFound = false,
                authorized = false,
                kind = null,
                notificationKey = null,
                actionIndex = null,
                semantic = null,
                outcome = "unauthorized",
            )
            answer(query, "Not authorised", alert = true)
            return
        }

        val token = callbackTokenDao.findByToken(data)
        if (token == null) {
            diag.callback(
                tokenFound = false,
                authorized = true,
                kind = null,
                notificationKey = null,
                actionIndex = null,
                semantic = null,
                outcome = "tokenExpired",
            )
            answer(query, "This button has expired", alert = true)
            return
        }

        when (RemoteActionKeyboards.kindOf(token.kind)) {
            RemoteActionKind.DISMISS -> applyAndReport(
                query,
                token,
                gateway.dismiss(token.notificationKey),
                "Dismissed",
            )

            // The generic case: fire the app's own action exactly as the phone would.
            RemoteActionKind.FIRE -> applyAndReport(
                query,
                token,
                gateway.fireAction(
                    key = token.notificationKey,
                    actionIndex = token.actionIndex,
                    semantic = token.semantic,
                    label = token.label,
                ),
                token.label.trim(),
            )

            // Inline buttons can't collect free text, so open the client's reply box instead.
            RemoteActionKind.REPLY -> promptForReply(query, token)

            null -> answer(query, "Unsupported action", alert = true)
        }
    }

    /**
     * Ask the user for the reply text.
     *
     * A `callback_query` answer is only a transient toast, and some clients (Telegram X) don't surface
     * it at all — so instead we post a **ForceReply** prompt. Clients respond to that by opening the
     * keyboard already targeted at the prompt, which both makes the instruction impossible to miss and
     * turns replying into a single tap. The prompt gets its own token row so the typed text routes
     * back to the same notification. If the prompt can't be sent we fall back to a modal alert, which
     * is far more visible than the default toast.
     */
    private suspend fun promptForReply(query: TgCallbackQuery, token: CallbackTokenEntity) {
        val prompt = runCatching {
            api.sendMessage(
                chatId = token.chatId,
                text = REPLY_PROMPT_TEXT,
                parseMode = null,
                replyMarkup = keyboards.forceReplyMarkup(REPLY_PROMPT_PLACEHOLDER),
                // Quote the forwarded message so the prompt is visibly attached to it.
                replyToMessageId = query.message?.messageId,
            )
        }.getOrNull()

        val promptId = prompt?.body()?.result?.messageId
        if (promptId == null) {
            answer(query, "Reply to the forwarded message and I'll send it", alert = true)
            return
        }
        // Route a reply to the prompt back to this notification's reply action.
        runCatching { keyboards.registerReplyPrompt(token, promptId) }
        answer(query, null)
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
            // Not a reply to anything of ours — the user is just chatting. Record nothing further.
            return
        }

        val result = gateway.reply(token.notificationKey, token.actionIndex, text, token.label)
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
        answer(query, outcomeText(result, successText), alert = result !is RemoteActionResult.Success)

        // Media playback controls must keep their whole menu: the notification survives every action,
        // so collapsing to one button would leave you able to pause but never resume. This covers the
        // dedicated now-playing control AND a media notification that was forwarded normally — the
        // latter is judged from the LIVE notification, so it holds however the message got here.
        if (token.isPersistentControl || gateway.isOngoingMedia(token.notificationKey)) return

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

    private fun outcomeText(result: RemoteActionResult, successText: String): String = when (result) {
        is RemoteActionResult.Success -> successText
        is RemoteActionResult.NotificationGone -> "Notification is no longer on the device"
        is RemoteActionResult.ListenerUnavailable -> "Notification access is not connected"
        is RemoteActionResult.ActionUnavailable -> "That action is no longer available"
        is RemoteActionResult.Failed -> "Failed: ${result.message}"
    }

    private fun replyOutcomeText(result: RemoteActionResult): String = when (result) {
        is RemoteActionResult.Success -> "✅ Sent"
        is RemoteActionResult.NotificationGone -> "⚠️ That notification is gone — can't reply to it any more"
        is RemoteActionResult.ListenerUnavailable -> "⚠️ Notification access is not connected"
        is RemoteActionResult.ActionUnavailable -> "⚠️ That chat can't be replied to from here"
        is RemoteActionResult.Failed -> "⚠️ Failed: ${result.message}"
    }

    /** A token is not authorisation: the press must come from the one paired chat. */
    private suspend fun isFromPairedChat(chatId: Long?): Boolean {
        if (chatId == null) return false
        return settings.chatId.first() == chatId
    }

    /**
     * Acknowledge the press. [alert] shows a modal dialog instead of the default toast — used whenever
     * the message actually matters, since some clients never surface the toast.
     */
    private suspend fun answer(query: TgCallbackQuery, text: String?, alert: Boolean = false) {
        runCatching {
            api.answerCallbackQuery(callbackQueryId = query.id, text = text, showAlert = alert)
        }
    }

    private companion object {
        const val REPLY_PROMPT_TEXT = "✍️ Reply to this message and I'll send it from your phone."
        const val REPLY_PROMPT_PLACEHOLDER = "Type your reply…"
    }
}
