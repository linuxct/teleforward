package space.linuxct.teleforward.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The debouncer's contract: coalesce a burst into its last update, and account for every payload.
 *
 * The accounting half matters as much as the coalescing. Each submission has already extracted album
 * art to disk before it gets here, so a superseded update that ran neither callback would leak a file
 * on every skipped track.
 */
class MediaDispatchDebouncerTest {

    private val debouncer = MediaDispatchDebouncer()

    /** Comfortably past the 1s window. */
    private suspend fun settle() = delay(1_600L)

    @Test
    fun aBurstCollapsesToItsLastUpdate() = runBlocking {
        val acted = CopyOnWriteArrayList<String>()
        val discarded = CopyOnWriteArrayList<String>()

        // Skipping through five tracks faster than the window.
        listOf("a", "b", "c", "d", "e").forEach { track ->
            debouncer.submit(
                key = "com.example.player",
                discard = { discarded += track },
                action = { acted += track },
            )
            delay(50L)
        }
        settle()

        // Only the track actually landed on is forwarded...
        assertEquals(listOf("e"), acted)
        // ...and every track passed over is accounted for, so its artwork file is released.
        assertEquals(listOf("a", "b", "c", "d"), discarded)
    }

    @Test
    fun everyPayloadRunsExactlyOneCallback() = runBlocking {
        val outcomes = CopyOnWriteArrayList<String>()
        repeat(8) { i ->
            debouncer.submit(
                key = "com.example.player",
                discard = { outcomes += "discard-$i" },
                action = { outcomes += "action-$i" },
            )
            delay(20L)
        }
        settle()

        // Eight submissions, eight outcomes: nothing ran twice and nothing vanished.
        assertEquals(8, outcomes.size)
        assertEquals(1, outcomes.count { it.startsWith("action-") })
    }

    @Test
    fun playersDebounceIndependently() = runBlocking {
        val acted = CopyOnWriteArrayList<String>()
        // One app skipping must never swallow another app's update.
        debouncer.submit("com.example.player", discard = {}, action = { acted += "music" })
        debouncer.submit("com.other.video", discard = {}, action = { acted += "video" })
        settle()

        assertEquals(setOf("music", "video"), acted.toSet())
    }

    @Test
    fun anUpdateAfterTheWindowIsNotCoalesced() = runBlocking {
        val acted = CopyOnWriteArrayList<String>()
        debouncer.submit("com.example.player", discard = {}, action = { acted += "first" })
        settle()
        debouncer.submit("com.example.player", discard = {}, action = { acted += "second" })
        settle()

        // A deliberate track change a while later is its own message, not a replacement.
        assertEquals(listOf("first", "second"), acted)
    }
}
