package space.linuxct.teleforward.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collapses a flurry of media updates for one app into the last one, after a short quiet period.
 *
 * ## Why
 *
 * Every media forward now costs a handful of Telegram calls — send, unpin, delete the previous track,
 * pin, then delete the pin notice. That is fine at one track every few minutes and badly wrong when
 * skipping: Telegram's guidance is about one message per second **per chat**, and a 429 throttles the
 * whole bot token, so a burst of skips would delay ordinary notification forwards too.
 *
 * Skipping five tracks in three seconds also used to post five messages and immediately delete four of
 * them. Coalescing is not just cheaper, it is what the feature already meant: only the track you
 * landed on is worth a message.
 *
 * ## Guarantee
 *
 * For every submitted payload, **exactly one** of its two callbacks runs — [action] if it survives the
 * window, [discard] if a newer update supersedes it. Nothing is silently dropped: `discard` is how the
 * caller releases what it had already prepared (extracted artwork files), so a superseded update
 * cannot leak the images it extracted.
 *
 * Artwork is deliberately *not* deferred by this class. A notification's bitmap may be recycled or
 * replaced within milliseconds, so the caller extracts eagerly and hands over a payload that is
 * already safe on disk; only the dispatch waits.
 *
 * Keyed per package, so two players debounce independently and one app's rapid skipping can never
 * hold up another's.
 */
@Singleton
class MediaDispatchDebouncer @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, Entry>()

    private class Entry(val job: Job, val discard: suspend () -> Unit)

    /**
     * Dispatch [action] for [key] once no newer update has arrived for [WINDOW_MS].
     *
     * A newer submission for the same key cancels this one and runs its [discard] instead.
     */
    suspend fun submit(
        key: String,
        discard: suspend () -> Unit,
        action: suspend () -> Unit,
    ) {
        mutex.withLock {
            pending.remove(key)?.let { previous ->
                previous.job.cancel()
                runCatching { previous.discard() }
            }
            val job = scope.launch {
                delay(WINDOW_MS)
                // Claim this slot before acting. Removing under the same lock a competing submit must
                // take is what makes "action or discard, never both" hold: once we are out of the map
                // nobody can cancel us, and while we are still in it we cannot have started.
                mutex.withLock { pending.remove(key) }
                runCatching { action() }
            }
            pending[key] = Entry(job, discard)
        }
    }

    private companion object {
        /**
         * Long enough to swallow a skip-through and the two-part track change a player posts (the
         * bare announcement, then the re-post carrying album art ~50ms later), short enough that a
         * deliberate track change still feels immediate.
         */
        const val WINDOW_MS = 1_000L
    }
}
