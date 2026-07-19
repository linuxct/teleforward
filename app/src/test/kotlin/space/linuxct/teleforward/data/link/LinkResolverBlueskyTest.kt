package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the Bluesky branch of [LinkResolverImpl.resolve]: a captured post AT-URI becomes a
 * `bsky.app/profile/<did>/post/<rkey>` url, while a follow, a chat message or a missing payload
 * correctly resolve to nothing. Pure: no Android, no network.
 */
class LinkResolverBlueskyTest {

    private val did = "did:plc:6kos45lixtga3pdwuncvh32x"
    private val rkey = "3mqc36slinc2m"

    private fun bskyItem(atUri: String? = null) = OutboxEntity(
        dedupeKey = "k",
        packageName = "xyz.blueskyweb.app",
        channelId = "likes",
        appLabel = "Bluesky",
        channelName = null,
        title = "someone.bsky.social",
        body = "liked your post",
        blueskyAtUri = atUri,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun postAtUriResolvesToBskyAppUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            bskyItem("at://$did/app.bsky.feed.post/$rkey"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://bsky.app/profile/$did/post/$rkey", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(Bluesky.SERVICE, result.trace.service)
    }

    @Test
    fun aFollowRecordIsNotLinkable() = runTest {
        val result = LinkResolverImpl().resolve(
            bskyItem("at://$did/app.bsky.graph.follow/3flwrkey00"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun aMissingPayloadIsAnExplainableMiss() = runTest {
        // Chat messages carry no post uri, and an OEM/Expo change could drop the blob entirely.
        val result = LinkResolverImpl().resolve(bskyItem(null), disabledPackages = emptySet())
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals(Bluesky.SERVICE, result.trace.service)
    }

    @Test
    fun optedOutPackageSkipsResolution() = runTest {
        val result = LinkResolverImpl().resolve(
            bskyItem("at://$did/app.bsky.feed.post/$rkey"),
            disabledPackages = setOf("xyz.blueskyweb.app"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
