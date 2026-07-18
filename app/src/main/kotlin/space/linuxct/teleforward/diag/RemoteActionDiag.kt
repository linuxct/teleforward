package space.linuxct.teleforward.diag

import kotlinx.coroutines.flow.first
import org.json.JSONObject
import space.linuxct.teleforward.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release-safe diagnostics for the remote-action feature, so a "the buttons don't work" report can be
 * explained from a dump instead of guessed at. Records the whole round trip:
 *
 *  - `attach`   — were buttons added to a forward, and if not, why not
 *  - `poll`     — is the app actually receiving updates (or is it unpaired / erroring / 409-conflicting)
 *  - `callback` — a press: did the token resolve, was it authorised, what did the device say
 *  - `reply`    — typed text routed back to a notification's reply action
 *
 * **Privacy:** never records message content, reply text, contact names or a raw notification key
 * (its tag can embed a chat identifier). Only the package name plus a short stable hash of the key,
 * which is enough to correlate events across a dump. Gated on the Diagnostics toggle and entirely
 * best-effort — it must never affect the action it is describing.
 */
@Singleton
class RemoteActionDiag @Inject constructor(
    private val diagStore: DiagStore,
    private val settings: SettingsRepository,
) {

    /** Why a forward did or didn't get buttons. */
    suspend fun attach(
        packageName: String,
        enabled: Boolean,
        hasNotificationKey: Boolean,
        actionCount: Int,
        buttonCount: Int,
        reason: String?,
    ) = record("attach") {
        put("packageName", packageName)
        put("remoteActionsEnabled", enabled)
        put("hasNotificationKey", hasNotificationKey)
        put("actionCount", actionCount)
        put("buttonCount", buttonCount)
        putOpt("reason", reason)
    }

    /** One getUpdates cycle: proves whether inbound polling is running at all. */
    suspend fun poll(
        updates: Int?,
        httpStatus: Int?,
        skippedReason: String?,
        error: String?,
    ) = record("poll") {
        putOpt("updates", updates)
        putOpt("httpStatus", httpStatus)
        putOpt("skipped", skippedReason)
        putOpt("error", error)
    }

    /** A button press and what the device did about it. */
    suspend fun callback(
        tokenFound: Boolean,
        authorized: Boolean,
        kind: String?,
        notificationKey: String?,
        actionIndex: Int?,
        semantic: Int?,
        outcome: String,
    ) = record("callback") {
        put("tokenFound", tokenFound)
        put("authorized", authorized)
        putOpt("kind", kind)
        putOpt("packageName", packageOf(notificationKey))
        putOpt("keyHash", hashOf(notificationKey))
        putOpt("actionIndex", actionIndex)
        putOpt("semantic", semantic)
        put("outcome", outcome)
    }

    /**
     * Whether the press was actually acknowledged to Telegram.
     *
     * Its own phase because "we handled the press" and "the user saw a result" are different claims,
     * and only this one distinguishes them. A press answered too late — the signature of a press that
     * landed while nothing was polling — shows up here as delivered=false with Telegram's own
     * rejection text, which is otherwise invisible in a dump.
     */
    suspend fun answer(delivered: Boolean, error: String?) = record("answer") {
        put("delivered", delivered)
        putOpt("error", error)
    }

    /** A typed reply routed back to a notification. [textLength] only — never the text itself. */
    suspend fun reply(
        matched: Boolean,
        notificationKey: String?,
        textLength: Int,
        outcome: String,
    ) = record("reply") {
        put("matched", matched)
        putOpt("packageName", packageOf(notificationKey))
        putOpt("keyHash", hashOf(notificationKey))
        put("textLength", textLength)
        put("outcome", outcome)
    }

    private suspend fun record(phase: String, build: JSONObject.() -> Unit) {
        runCatching {
            if (!settings.diagnosticsEnabled.first()) return
            val json = JSONObject().apply {
                put("kind", "remoteActionTrace")
                put("phase", phase)
                put("capturedAt", System.currentTimeMillis())
                build()
            }
            diagStore.append(ForensicRecord(json.toString()))
        }
    }

    /**
     * The package from a `StatusBarNotification.key` (`userId|pkg|id|tag|uid`). Safe to log; the rest
     * of the key is not, because an app may put a chat identifier in the tag.
     */
    private fun packageOf(key: String?): String? =
        key?.split('|')?.getOrNull(1)?.takeUnless { it.isBlank() }

    /** Short non-reversible digest so events for one notification can be correlated in a dump. */
    private fun hashOf(key: String?): String? {
        if (key == null) return null
        var hash = 0
        for (char in key) hash = hash * 31 + char.code
        return Integer.toHexString(hash)
    }
}
