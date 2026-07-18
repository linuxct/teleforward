package space.linuxct.teleforward.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A notification action button we captured at forward time and may re-fire remotely later.
 *
 * Deliberately metadata-only: the action's `PendingIntent` itself is NOT stored (it can't be, and it
 * dies with the process). At fire time the live notification is re-resolved by key and the action is
 * located again — see `NotificationActionGateway`.
 *
 * @property index position in `Notification.actions`.
 * @property title the button label as the source app wrote it.
 * @property semantic `Notification.Action.getSemanticAction()`; **usually 0** — most apps never set
 *   it (K-9 Mail and every media app report 0), which is why it must never be a precondition for
 *   offering an action. Used only as a preferred locator and for nicer labels.
 * @property remoteInput true when the action carries a `RemoteInput` (it expects typed text).
 * @property immutable true when the action's `PendingIntent` is immutable. This blocks *injecting* a
 *   reply, but NOT firing the action — most actions are immutable and fire perfectly.
 * @property opensApp true when the action's `PendingIntent` is an **activity**, i.e. firing it opens
 *   the app on the phone rather than performing a silent background action.
 */
@Serializable
data class NotificationActionInfo(
    val index: Int,
    val title: String,
    val semantic: Int = 0,
    val remoteInput: Boolean = false,
    val immutable: Boolean = false,
    val opensApp: Boolean = false,
) {
    /**
     * Whether typed text can actually be delivered through this action. Requires a `RemoteInput` AND a
     * mutable `PendingIntent` — an immutable one silently discards the injected text.
     */
    val canReply: Boolean get() = remoteInput && !immutable

    companion object {
        /** `Notification.Action.SEMANTIC_ACTION_REPLY`. */
        const val SEMANTIC_REPLY = 1

        /** `Notification.Action.SEMANTIC_ACTION_MARK_AS_READ`. */
        const val SEMANTIC_MARK_AS_READ = 2
    }
}

/** What a remote button does when pressed. */
enum class RemoteActionKind {
    /** Dismiss the notification on the device (goes through the listener, not the source app). */
    DISMISS,

    /** Deliver typed text through the action's `RemoteInput`. */
    REPLY,

    /** Fire the action's `PendingIntent` as-is — the generic case that works for any app. */
    FIRE,
}

/** A button to render under a forwarded message: what it does, its label, and how to find the action. */
data class RemoteActionButton(
    val kind: RemoteActionKind,
    val label: String,
    /** Index into `Notification.actions`; -1 for [RemoteActionKind.DISMISS], which needs no action. */
    val actionIndex: Int = NO_ACTION,
    /** Preferred locator at fire time; 0 when the action exposed no semantic. */
    val semantic: Int = 0,
) {
    companion object {
        const val NO_ACTION = -1
    }
}

/**
 * Wording for the buttons this app adds or renames itself.
 *
 * Passed in rather than read from resources so [remoteButtons] stays a pure, Android-free function
 * with fast unit tests, while the strings themselves still live in `strings.xml` and get translated.
 * An app's own action titles are never touched — only these four are ours to word.
 */
data class ButtonLabels(
    val dismiss: String,
    val playPause: String,
    /** Used when an action carries a reply field but no title of its own. */
    val replyFallback: String,
    /** Used when an action has no title at all. */
    val actionFallback: String,
)

/**
 * Pure: build the remote button set for a forwarded notification.
 *
 * The guiding rule is **mirror what the phone actually offers**. Earlier this keyed off
 * `semanticAction`, which meant only WhatsApp-style messengers got buttons — virtually every other
 * app (K-9 Mail's Mark Read/Delete, every media transport control) reports semantic 0 and got
 * nothing, even though those actions fire perfectly. So each action is taken on its own merits:
 *
 *  - carries a `RemoteInput` we can fill → [RemoteActionKind.REPLY]
 *  - carries a `RemoteInput` we CANNOT fill (immutable) → skipped, because firing it bare would at
 *    best do nothing and at worst send an empty message
 *  - anything else → [RemoteActionKind.FIRE], a plain `PendingIntent.send()`. Immutability is
 *    irrelevant here, which is what makes generic support possible at all.
 *
 * Labels stay honest: the app's own wording, with [OPENS_APP_MARKER] appended when firing will open
 * the app on the phone instead of acting silently. Dismiss is always offered and always last.
 */
fun remoteButtons(
    actions: List<NotificationActionInfo>,
    /** Wording for the buttons this app owns; see [ButtonLabels]. */
    labels: ButtonLabels,
    /**
     * Set false for notifications the system won't let a listener clear — anything ongoing /
     * `NO_CLEAR` / foreground-service, i.e. every media notification. `cancelNotification()` is
     * silently refused for those, so offering Dismiss would be a button that does nothing. Media
     * notifications expose their own `Stop`, which is the honest equivalent.
     */
    includeDismiss: Boolean = true,
    /**
     * True to label a toggle by **what it does** rather than by the state it is currently in.
     *
     * The case that matters is play/pause: a button captured reading "Pause" still reads "Pause"
     * after you press it, when pressing it would now *play*. Both media paths set this. A one-shot
     * forward must, since the message is never updated at all — but the now-playing control does too,
     * even though it re-renders: the correcting edit races the press, so during the moment you are
     * actually looking at the button the live wording is the least trustworthy. A button that plainly
     * names the toggle is never wrong, whereas one that names a state is wrong exactly when used.
     */
    stableLabels: Boolean = false,
): List<RemoteActionButton> {
    val out = mutableListOf<RemoteActionButton>()

    for (action in actions) {
        if (out.size >= MAX_ACTION_BUTTONS) break
        // A reply field we can't write to is worse than no button at all.
        if (action.remoteInput && !action.canReply) continue

        val kind = if (action.canReply) RemoteActionKind.REPLY else RemoteActionKind.FIRE
        out += RemoteActionButton(
            kind = kind,
            label = buttonLabel(action, kind, stableLabels, labels),
            actionIndex = action.index,
            semantic = action.semantic,
        )
    }

    if (includeDismiss) out += RemoteActionButton(RemoteActionKind.DISMISS, labels.dismiss)
    return out
}

/**
 * Pure: is this a media/transport notification (a "now playing" card)? Those are ongoing and
 * `NO_CLEAR`, so they never reach the outbox — they drive the now-playing remote control instead.
 */
fun isMediaNotification(
    category: String?,
    template: String?,
    hasMediaSession: Boolean = false,
): Boolean =
    category == CATEGORY_TRANSPORT ||
        template?.contains("MediaStyle") == true ||
        // Strongest signal, and package-agnostic: any player attaching a MediaSession token is a
        // media notification even if it sets no transport category and an unfamiliar template.
        hasMediaSession

/** `android.app.Notification.CATEGORY_TRANSPORT`. */
private const val CATEGORY_TRANSPORT = "transport"

/** The app's own label, marked up so the effect of pressing it is never a surprise. */
private fun buttonLabel(
    action: NotificationActionInfo,
    kind: RemoteActionKind,
    stableLabels: Boolean,
    labels: ButtonLabels,
): String {
    // On a message that never updates, "Pause" becomes a lie as soon as it works. Name the toggle.
    if (stableLabels && isPlayPauseToggle(action.title)) return labels.playPause

    val base = action.title.trim().ifEmpty { if (kind == RemoteActionKind.REPLY) labels.replyFallback else labels.actionFallback }
    val truncated = if (base.length > MAX_LABEL_LENGTH) {
        base.take(MAX_LABEL_LENGTH - 1).trimEnd() + "…"
    } else {
        base
    }
    return when {
        kind == RemoteActionKind.REPLY -> "💬 $truncated"
        action.opensApp -> "$truncated $OPENS_APP_MARKER"
        action.semantic == NotificationActionInfo.SEMANTIC_MARK_AS_READ -> "✅ $truncated"
        else -> truncated
    }
}

/** Appended to a button whose action opens the app on the phone rather than acting in the background. */
const val OPENS_APP_MARKER = "↗"

// NOTE: shuffle/repeat state is deliberately NOT shown on these buttons. A notification carries no
// trace of it — across 69 captured samples the "Shuffle" and "Repeat" titles and icons are byte
// identical whether on or off, and no extra mentions either (unlike Play/Pause, whose title really
// does change, which is why that one is handled above). The only source is the player's media
// session, and reading it needs a long-lived MediaControllerCompat per player plus an asynchronous
// extra-binder handshake — a throwaway controller reads SHUFFLE_MODE_INVALID most of the time, which
// is why an earlier attempt showed a label once and never again.


/**
 * Is this the transport play/pause toggle — the one action whose label flips underneath a message
 * that never updates? Matched on the label because media actions carry no `semanticAction` (every
 * player reports 0). Players already disagree on the wording, and one of the captured YouTube states
 * literally reads "Play/Pause", so all three spellings are treated as the same control.
 *
 * English and Spanish wording are recognised; a player localised into another language keeps its own
 * label, which is no worse than before.
 */
private fun isPlayPauseToggle(title: String): Boolean =
    title.trim().lowercase().replace(" ", "") in setOf(
        "play", "pause", "play/pause", "reproducir", "pausar", "continuar"
    )


/** Keeps the keyboard usable: media notifications alone can expose nine actions. */
private const val MAX_ACTION_BUTTONS = 6

/** Telegram renders long button text poorly; keep labels short. */
private const val MAX_LABEL_LENGTH = 22

/** Compact JSON codec for [NotificationActionInfo] lists, stored in a single `outbox` column. */
object NotificationActions {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Encode for storage; null when there is nothing worth storing. */
    fun encode(actions: List<NotificationActionInfo>): String? =
        if (actions.isEmpty()) null else runCatching { json.encodeToString(actions) }.getOrNull()

    /** Decode from storage; an unreadable/absent value yields an empty list (never throws). */
    fun decode(raw: String?): List<NotificationActionInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<NotificationActionInfo>>(raw) }
            .getOrDefault(emptyList())
    }
}
