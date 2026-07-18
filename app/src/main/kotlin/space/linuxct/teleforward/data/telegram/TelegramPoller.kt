package space.linuxct.teleforward.data.telegram

import kotlinx.coroutines.flow.first
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.diag.RemoteActionDiag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One `getUpdates` cycle for the remote-action feature.
 *
 * Since the app is the bot and there is no server, inbound button presses can only arrive by polling.
 * Each cycle long-polls (Telegram holds the request open until an update arrives or the timeout
 * elapses), dispatches whatever came back, and advances the offset.
 *
 * Design notes:
 *  - `allowed_updates` must include `callback_query`, and is passed explicitly on every call. It is
 *    sticky server-side state rather than a per-request filter, so a single narrow call anywhere in
 *    the app tells Telegram to stop queueing button presses until something widens it again.
 *  - The offset lives under its own settings key so it never overwrites the pairing offset. Note that
 *    Telegram's update queue is still shared per bot — while polling is active it consumes updates,
 *    which is why polling is skipped entirely until a chat is paired.
 *  - [POLL_TIMEOUT_SECONDS] is deliberately well under the shared OkHttp read timeout (60s), so a
 *    long poll can never be mistaken for a dead connection.
 */
@Singleton
class TelegramPoller @Inject constructor(
    private val api: TelegramApi,
    private val settings: SettingsRepository,
    private val dispatcher: RemoteActionDispatcher,
    private val pinner: ChatPinner,
    private val diag: RemoteActionDiag,
) {

    /**
     * Poll once and dispatch. Returns true when the cycle completed cleanly (whether or not anything
     * arrived); false on an error the caller should treat as "back off / stop the burst".
     */
    suspend fun pollOnce(timeoutSeconds: Int = POLL_TIMEOUT_SECONDS): Boolean {
        // Never disturb onboarding: before pairing, the user's own /start must reach the pairing call.
        if (settings.chatId.first() == null) {
            diag.poll(updates = null, httpStatus = null, skippedReason = "notPaired", error = null)
            return false
        }

        val offset = settings.remoteActionsOffset.first().takeIf { it > 0L }
        val response = try {
            api.getUpdates(
                offset = offset,
                timeout = timeoutSeconds,
                allowedUpdates = TelegramApi.ALLOWED_UPDATES,
            )
        } catch (t: Throwable) {
            // Network hiccup or a cancelled long poll: let the caller decide whether to retry.
            diag.poll(
                updates = null,
                httpStatus = null,
                skippedReason = null,
                error = t.message ?: t.javaClass.simpleName,
            )
            return false
        }

        // 409 means another getUpdates consumer is active (e.g. the pairing capture) — yield to it.
        if (!response.isSuccessful) {
            diag.poll(
                updates = null,
                httpStatus = response.code(),
                skippedReason = if (response.code() == HTTP_CONFLICT) "conflict" else null,
                error = null,
            )
            return false
        }
        val updates = response.body()?.result.orEmpty()
        diag.poll(updates = updates.size, httpStatus = response.code(), skippedReason = null, error = null)
        if (updates.isEmpty()) return true

        for (update in updates) {
            // Per-update isolation: one bad update must not abort the rest of the batch.
            runCatching {
                update.callbackQuery?.let { dispatcher.handleCallback(it) }
                update.message?.let { message ->
                    // Telegram announces every pin with a service message, even in a private chat.
                    // Ours are deleted here; anything else falls through to the reply relay.
                    if (!pinner.consumePinNotice(message)) dispatcher.handleMessage(message)
                }
            }
            // Confirm each update the moment it has been handled, rather than the whole batch at the
            // end. If the worker is stopped part-way through (WorkManager reclaiming its slot, the
            // service dying), only the update actually in flight can be redelivered — previously the
            // entire batch was, which meant re-firing presses that had already succeeded. Dismissing
            // twice is harmless; toggling playback twice is not.
            runCatching { settings.setRemoteActionsOffset(update.updateId + 1) }
        }
        return true
    }

    companion object {
        /** Long-poll window. Comfortably below the 60s OkHttp read timeout. */
        const val POLL_TIMEOUT_SECONDS = 25

        /** Telegram's "another getUpdates is already running" status. */
        private const val HTTP_CONFLICT = 409
    }
}
