package space.linuxct.teleforward.data.telegram

import kotlinx.serialization.json.Json
import space.linuxct.teleforward.data.db.dao.CallbackTokenDao
import space.linuxct.teleforward.data.db.entity.CallbackTokenEntity
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.telegram.dto.TgForceReply
import space.linuxct.teleforward.data.telegram.dto.TgInlineKeyboardButton
import space.linuxct.teleforward.data.telegram.dto.TgInlineKeyboardMarkup
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.NotificationActions
import space.linuxct.teleforward.domain.RemoteActionKind
import space.linuxct.teleforward.domain.remoteButtons
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Turns a forwarded item's captured notification actions into Telegram **inline buttons** — the
 * per-message button row rendered underneath that one message (not a chat-wide reply keyboard, so
 * every forward keeps its own working buttons indefinitely).
 *
 * A button's `callback_data` is capped at 64 bytes, nowhere near enough for a notification key, so
 * each button carries a short random token and [CallbackTokenDao] holds the real target. Those rows
 * double as the record of which buttons a message has, so an edit can re-attach the same keyboard
 * (editing without `reply_markup` would silently strip the buttons).
 */
@Singleton
class RemoteActionKeyboards @Inject constructor(
    private val callbackTokenDao: CallbackTokenDao,
    private val json: Json,
    private val strings: TelegramStrings,
) {

    /**
     * Create the token rows for [row]'s curated buttons and return the serialized `reply_markup`, or
     * null when this item has nothing actionable (no notification key, or actions we don't surface).
     * Tokens are written before the send; [attachToMessage] links them to the message afterwards.
     */
    suspend fun createKeyboard(
        row: OutboxEntity,
        chatId: Long,
        now: Long,
        /** False for an ongoing media notification: Android refuses to let a listener clear those. */
        includeDismiss: Boolean = true,
        /** True for a media notification: this message never updates, so state labels would go stale. */
        stableLabels: Boolean = false,
    ): String? {
        val notificationKey = row.notificationKey ?: return null
        val buttons = remoteButtons(
            actions = NotificationActions.decode(row.actionsJson),
            labels = strings.buttonLabels,
            includeDismiss = includeDismiss,
            stableLabels = stableLabels,
        )
        if (buttons.isEmpty()) return null

        val expiresAt = now + TOKEN_TTL_MS
        val tokens = buttons.mapIndexed { position, button ->
            CallbackTokenEntity(
                token = newToken(),
                kind = button.kind.name,
                notificationKey = notificationKey,
                actionIndex = button.actionIndex,
                semantic = button.semantic,
                label = button.label,
                position = position,
                chatId = chatId,
                messageId = null,
                outboxId = row.id,
                expiresAt = expiresAt,
                createdAt = now,
            )
        }
        tokens.forEach { callbackTokenDao.insert(it) }
        return markup(tokens)
    }

    /** Link every not-yet-attached token for [outboxId] to the message that carried the keyboard. */
    suspend fun attachToMessage(outboxId: Long, messageId: Long) {
        callbackTokenDao.attachToMessage(outboxId, messageId)
    }

    /**
     * Register a second route to [source]'s reply action, bound to a ForceReply prompt message, so a
     * reply typed against that prompt reaches the same notification.
     *
     * A fresh [token] is minted rather than reusing the source row's — `callback_tokens.token` is
     * UNIQUE, so copying it would fail the insert and silently break the prompt.
     */
    suspend fun registerReplyPrompt(source: CallbackTokenEntity, promptMessageId: Long) {
        callbackTokenDao.insert(
            source.copy(id = 0L, token = newToken(), messageId = promptMessageId),
        )
    }

    /**
     * Rebuild a message's buttons from [actions], replacing whatever it had. Used by the now-playing
     * control, whose tokens must point at the notification that is live *now*. Dismiss is omitted:
     * media notifications are `NO_CLEAR` and the system refuses to let a listener cancel them.
     *
     * `stableLabels` is on here for the same reason it is on a one-shot media forward: the transport
     * toggle is labelled "⏯ Play/Pause" rather than mirroring the player's current word. A live
     * "Pause" label is only accurate until you press it — the edit that would correct it races the
     * press, and a button whose meaning depends on how fresh the message is is worse than one that
     * plainly names the toggle.
     */
    suspend fun replaceKeyboardForMessage(
        notificationKey: String,
        actions: List<NotificationActionInfo>,
        chatId: Long,
        messageId: Long,
        now: Long,
    ): String? {
        val buttons = remoteButtons(actions, strings.buttonLabels, includeDismiss = false, stableLabels = true)
        callbackTokenDao.deleteForMessage(chatId, messageId)
        if (buttons.isEmpty()) return null

        val tokens = buttons.mapIndexed { position, button ->
            CallbackTokenEntity(
                token = newToken(),
                kind = button.kind.name,
                notificationKey = notificationKey,
                actionIndex = button.actionIndex,
                semantic = button.semantic,
                label = button.label,
                position = position,
                chatId = chatId,
                messageId = messageId,
                outboxId = CallbackTokenEntity.NO_OUTBOX,
                expiresAt = now + TOKEN_TTL_MS,
                createdAt = now,
            )
        }
        tokens.forEach { callbackTokenDao.insert(it) }
        return markup(tokens)
    }

    /**
     * The serialized keyboard already attached to a message, or null if it has none. Used when editing
     * a message (to append a magic link) so its buttons survive the edit.
     */
    suspend fun keyboardForMessage(chatId: Long, messageId: Long): String? {
        val tokens = callbackTokenDao.findByMessage(chatId, messageId)
        return if (tokens.isEmpty()) null else markup(tokens)
    }

    /**
     * Serialize [tokens] as an `inline_keyboard`, wrapped into rows so a notification with several
     * actions (media apps expose up to nine) stays readable instead of one squashed row.
     */
    fun markup(tokens: List<CallbackTokenEntity>): String {
        val rows = tokens.sortedBy { it.position }
            .map { TgInlineKeyboardButton(text = it.label, callbackData = it.token) }
            .chunked(BUTTONS_PER_ROW)
        return json.encodeToString(TgInlineKeyboardMarkup(rows))
    }

    /**
     * A `ForceReply` markup: clients open the input box already targeted at the message carrying it,
     * which is how the user is prompted for reply text (a callback answer is only a toast, and some
     * clients drop it entirely).
     */
    fun forceReplyMarkup(placeholder: String): String =
        json.encodeToString(TgForceReply(placeholder = placeholder))

    /** Serialize a keyboard reflecting a completed action, so pressed buttons stop inviting presses. */
    fun resolvedMarkup(label: String): String =
        json.encodeToString(
            TgInlineKeyboardMarkup(
                listOf(listOf(TgInlineKeyboardButton(text = label, callbackData = NOOP_TOKEN))),
            ),
        )

    /** Drop a message's buttons entirely (its control has been retired). */
    suspend fun clearKeyboardForMessage(chatId: Long, messageId: Long) {
        runCatching { callbackTokenDao.deleteForMessage(chatId, messageId) }
    }

    /** Sweep expired rows so stale buttons resolve to "no longer available" instead of lingering. */
    suspend fun purgeExpired(now: Long) {
        runCatching { callbackTokenDao.deleteExpired(now) }
    }

    private fun newToken(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[Random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    companion object {
        /** Reserved `callback_data` for a button that is only a status label. */
        const val NOOP_TOKEN = "-"

        /** Buttons stop working after this; the notification is long gone by then anyway. */
        private const val TOKEN_TTL_MS = 7L * 24L * 60L * 60L * 1000L

        private const val TOKEN_LENGTH = 12
        private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Keeps buttons legible when an app exposes many actions. */
        private const val BUTTONS_PER_ROW = 3

        /** Kind lookup used by the dispatcher when resolving a pressed token. */
        fun kindOf(raw: String): RemoteActionKind? =
            RemoteActionKind.entries.firstOrNull { it.name == raw }
    }
}
