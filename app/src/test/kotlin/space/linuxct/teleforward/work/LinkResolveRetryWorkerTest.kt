package space.linuxct.teleforward.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the retry worker's give-up / backoff decision and backoff schedule (the
 * companion helpers), independent of Room / WorkManager / the network.
 */
class LinkResolveRetryWorkerTest {

    private val farFuture = Long.MAX_VALUE

    @Test
    fun keepsRetryingWhenAttemptsAndWindowRemain() {
        val decision = LinkResolveRetryWorker.decideRetry(
            attemptCount = 0,
            nowMs = 0L,
            expiresAtMs = farFuture,
        )
        assertFalse(decision.giveUp)
        // First retry after a miss uses the attempt-1 backoff (~2m).
        assertEquals(LinkResolveRetryWorker.backoffMillis(1), decision.nextDelayMs)
    }

    @Test
    fun givesUpWhenNextAttemptReachesMax() {
        // attemptCount 4 → nextAttempt 5 == MAX_ATTEMPTS → give up.
        val decision = LinkResolveRetryWorker.decideRetry(
            attemptCount = LinkResolveRetryWorker.MAX_ATTEMPTS - 1,
            nowMs = 0L,
            expiresAtMs = farFuture,
        )
        assertTrue(decision.giveUp)
    }

    @Test
    fun givesUpWhenPastExpiryWindow() {
        val decision = LinkResolveRetryWorker.decideRetry(
            attemptCount = 0,
            nowMs = 100L,
            expiresAtMs = 50L,
        )
        assertTrue(decision.giveUp)
    }

    @Test
    fun nextDelayMatchesBackoffForEachRemainingAttempt() {
        // attemptCount c (< MAX-1) schedules the (c+1)-th retry's backoff.
        for (c in 0 until LinkResolveRetryWorker.MAX_ATTEMPTS - 1) {
            val decision = LinkResolveRetryWorker.decideRetry(c, nowMs = 0L, expiresAtMs = farFuture)
            assertFalse("attempt $c should keep retrying", decision.giveUp)
            assertEquals(LinkResolveRetryWorker.backoffMillis(c + 1), decision.nextDelayMs)
        }
    }

    @Test
    fun backoffIsMonotonicallyIncreasingThenClamped() {
        val one = LinkResolveRetryWorker.backoffMillis(1)
        val two = LinkResolveRetryWorker.backoffMillis(2)
        val three = LinkResolveRetryWorker.backoffMillis(3)
        val four = LinkResolveRetryWorker.backoffMillis(4)
        assertTrue(one < two)
        assertTrue(two < three)
        assertTrue(three < four)
        // Attempt 1's step is ~2 minutes; past the table it clamps to the last (attempt-4) step.
        assertEquals(2L * 60L * 1000L, one)
        assertEquals(four, LinkResolveRetryWorker.backoffMillis(5))
        assertEquals(four, LinkResolveRetryWorker.backoffMillis(99))
    }
}
