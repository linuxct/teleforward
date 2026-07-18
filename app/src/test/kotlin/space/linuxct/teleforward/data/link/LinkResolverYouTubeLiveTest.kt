package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * The live-stream / premiere path of [LinkResolverImpl.resolve]: a notification that names its VIDEO
 * id resolves straight to the watch url with **no network at all** (no uploads feed, no search).
 *
 * All ids below are synthetic placeholders.
 */
class LinkResolverYouTubeLiveTest {

    private fun ytItem(
        videoId: String? = null,
        channelId: String? = null,
        body: String? = "Some Stream Title",
    ) = OutboxEntity(
        dedupeKey = "k",
        packageName = "com.google.android.youtube",
        channelId = "uploads",
        appLabel = "YouTube",
        channelName = null,
        title = "🔴 Some Channel",
        body = body,
        youtubeChannelId = channelId,
        youtubeVideoId = videoId,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun videoIdShortCircuitsToWatchUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            ytItem(videoId = "PremiereVid"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://www.youtube.com/watch?v=PremiereVid", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals("PremiereVid", result.trace.videoId)
        // Proves the direct path ran rather than the feed/search path.
        assertEquals("slotKey", result.trace.source)
    }

    @Test
    fun videoIdWinsEvenWithoutATitle() = runTest {
        // The upload path needs a title; the live path does not, because nothing has to be matched.
        val result = LinkResolverImpl().resolve(
            ytItem(videoId = "LiveVid_002", body = null),
            disabledPackages = emptySet(),
        )
        assertEquals("https://www.youtube.com/watch?v=LiveVid_002", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
    }

    @Test
    fun disabledPackageStillSkipsLiveEvents() = runTest {
        val result = LinkResolverImpl().resolve(
            ytItem(videoId = "PremiereVid"),
            disabledPackages = setOf("com.google.android.youtube"),
        )
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }

    @Test
    fun noVideoIdAndNoChannelIdIsAMiss() = runTest {
        // Neither identifier present: must not attempt anything, and must not crash.
        val result = LinkResolverImpl().resolve(ytItem(), disabledPackages = emptySet())
        assertEquals(MagicLinkOutcome.NO_CHANNEL_ID, result.trace.outcome)
    }
}
