package space.linuxct.teleforward.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.isMediaNotification
import javax.inject.Inject
import javax.inject.Singleton

/** `Notification.EXTRA_TEMPLATE`. */
private const val TEMPLATE_EXTRA = "android.template"

/** `Notification.EXTRA_MEDIA_SESSION` — present on any player's notification. */
private const val MEDIA_SESSION_EXTRA = "android.mediaSession"

/**
 * The subset of the live [android.service.notification.NotificationListenerService] that
 * [NotificationActionGateway] needs. Implemented by [TeleNotificationListener]; kept as an interface
 * so the gateway can be reasoned about (and faked) without the framework service.
 */
interface NotificationHost {
    /** The still-posted notification with this key, or null when it is gone from the device. */
    fun activeNotificationByKey(key: String): StatusBarNotification?

    /** Dismiss the notification with this key, as if the user swiped it away. */
    fun cancel(key: String)
}

/** Outcome of a remote action, mapped to user-facing text by the Telegram dispatcher. */
sealed interface RemoteActionResult {
    data object Success : RemoteActionResult

    /** The notification is no longer on the device (already read/dismissed elsewhere). */
    data object NotificationGone : RemoteActionResult

    /** The notification listener isn't connected right now (permission revoked, service killed). */
    data object ListenerUnavailable : RemoteActionResult

    /** The notification is present but no longer exposes that action (or it can't accept a reply). */
    data object ActionUnavailable : RemoteActionResult

    data class Failed(val message: String) : RemoteActionResult
}

/**
 * Performs notification actions on behalf of remote (Telegram) button presses.
 *
 * Two capabilities, both public API and neither requiring accessibility:
 *  - **Dismiss** via `NotificationListenerService.cancelNotification(key)`.
 *  - **Fire an action** by `PendingIntent.send()`. Note the asymmetry that makes this possible at all:
 *    *reading* another app's `PendingIntent` is blocked cross-UID, but *sending* one is not. For a
 *    reply, a `RemoteInput` result bundle is filled into the send — which only works if the
 *    `PendingIntent` is **mutable** (WhatsApp's and Telegram X's reply actions are).
 *
 * The live service is attached in `onListenerConnected` and detached on disconnect/destroy, so this
 * singleton is the one bridge from background code (workers) back into the listener. `Notification`
 * and `Action` objects are never cached — they die with the process — so every call re-resolves the
 * notification by key, which also makes staleness detection automatic.
 */
@Singleton
class NotificationActionGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var host: NotificationHost? = null

    /** Called by the listener once it is connected to the framework. */
    fun attach(host: NotificationHost) {
        this.host = host
    }

    /** Called on disconnect/destroy; ignores a stale detach from a superseded instance. */
    fun detach(host: NotificationHost) {
        if (this.host === host) this.host = null
    }

    /** True while a connected listener is available to act on. */
    val isAvailable: Boolean get() = host != null

    /** Dismiss the notification identified by [key]. */
    fun dismiss(key: String): RemoteActionResult {
        val host = host ?: return RemoteActionResult.ListenerUnavailable
        // Resolve first so an already-gone notification reports honestly instead of silently no-op'ing.
        if (host.activeNotificationByKey(key) == null) return RemoteActionResult.NotificationGone
        return runCatching {
            host.cancel(key)
            RemoteActionResult.Success
        }.getOrElse { RemoteActionResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Fire the action on notification [key], optionally supplying [replyText] for a `RemoteInput`
     * action. The action is located by [semantic] where possible (robust against the app re-posting
     * with a reordered action list) and by [actionIndex] otherwise.
     */
    fun fireAction(
        key: String,
        actionIndex: Int,
        semantic: Int = 0,
        replyText: String? = null,
        label: String? = null,
    ): RemoteActionResult {
        val host = host ?: return RemoteActionResult.ListenerUnavailable
        val sbn = host.activeNotificationByKey(key) ?: return RemoteActionResult.NotificationGone
        val action = resolveAction(sbn, actionIndex, semantic, label)
            ?: return RemoteActionResult.ActionUnavailable
        val pendingIntent = action.actionIntent ?: return RemoteActionResult.ActionUnavailable

        return try {
            if (replyText == null) {
                pendingIntent.send()
            } else {
                val remoteInputs = action.remoteInputs
                if (remoteInputs.isNullOrEmpty()) return RemoteActionResult.ActionUnavailable
                // An immutable PendingIntent silently discards the filled-in reply, so refuse rather
                // than pretend the message was sent.
                if (isImmutable(pendingIntent)) return RemoteActionResult.ActionUnavailable
                pendingIntent.send(context, 0, replyIntent(remoteInputs, replyText))
            }
            RemoteActionResult.Success
        } catch (canceled: PendingIntent.CanceledException) {
            // The source app revoked the intent — effectively the notification is gone.
            RemoteActionResult.NotificationGone
        } catch (t: Throwable) {
            RemoteActionResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Build the fill-in Intent carrying [text] for every [remoteInputs] result key. */
    private fun replyIntent(remoteInputs: Array<RemoteInput>, text: String): Intent {
        val intent = Intent()
        val results = Bundle()
        for (input in remoteInputs) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Tell the app a human typed this, so it isn't treated as a smart-reply choice.
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return intent
    }

    /**
     * Locate the action to fire on the *current* notification, which may have been re-posted since we
     * captured it. Order: a semantic match (stable across reordering, when the app sets one at all),
     * then the captured index, then the label.
     *
     * Index before label is deliberate for media notifications: a play/pause toggle swaps the label at
     * the same index, and the user pressing "Pause" then wants whatever that slot now does.
     */
    private fun resolveAction(
        sbn: StatusBarNotification,
        actionIndex: Int,
        semantic: Int,
        label: String? = null,
    ): Notification.Action? {
        val actions = sbn.notification.actions ?: return null
        if (semantic != 0) {
            actions.firstOrNull { semanticOf(it) == semantic }?.let { return it }
        }
        actions.getOrNull(actionIndex)?.let { return it }
        if (label != null) {
            actions.firstOrNull { it.title?.toString()?.trim().equals(label.trim(), ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun semanticOf(action: Notification.Action): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0

    private fun isImmutable(pendingIntent: PendingIntent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pendingIntent.isImmutable

    /**
     * Is the notification behind [key] an **ongoing media** notification right now?
     *
     * Answered from the live notification rather than anything stored, so it is true regardless of how
     * the message was forwarded. Such a notification survives every action — pausing doesn't remove
     * it — so its buttons must stay usable, and it can't be dismissed at all (`NO_CLEAR`), so Dismiss
     * shouldn't be offered for it in the first place.
     */
    fun isOngoingMedia(key: String): Boolean {
        val sbn = host?.activeNotificationByKey(key) ?: return false
        val notification = sbn.notification
        val media = isMediaNotification(
            category = notification.category,
            template = notification.extras?.getString(TEMPLATE_EXTRA),
            hasMediaSession = notification.extras?.containsKey(MEDIA_SESSION_EXTRA) == true,
        )
        val ongoing = notification.flags and
            (
                Notification.FLAG_ONGOING_EVENT or
                    Notification.FLAG_NO_CLEAR or
                    Notification.FLAG_FOREGROUND_SERVICE
                ) != 0
        return media && ongoing
    }

    /** Convenience for the dispatcher: send [text] through the captured reply action. */
    fun reply(key: String, actionIndex: Int, text: String, label: String? = null): RemoteActionResult =
        fireAction(key, actionIndex, NotificationActionInfo.SEMANTIC_REPLY, text, label)
}
