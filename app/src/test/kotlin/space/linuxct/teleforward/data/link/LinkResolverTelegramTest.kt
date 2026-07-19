package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the Telegram branch of [LinkResolverImpl.resolve]: a group/supergroup dismissal id resolves to
 * a `t.me/c/` message url, while private and secret chats — which Telegram exposes no shareable
 * per-message link for — deliberately resolve to nothing. Pure: no Android, no network.
 *
 * Ids are synthetic placeholders.
 */
class LinkResolverTelegramTest {

    private fun tgItem(dismissalId: String? = null) = OutboxEntity(
        dedupeKey = "k",
        packageName = "org.telegram.messenger",
        channelId = "messages",
        appLabel = "Telegram",
        channelName = null,
        title = "Some Group",
        body = "hi",
        telegramDismissalId = dismissalId,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun supergroupResolvesToMessageUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            tgItem("tgchat1234567890_4567"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://t.me/c/1234567890/4567", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(Telegram.SERVICE, result.trace.service)
        assertEquals("chat", result.trace.source)
    }

    @Test
    fun privateChatIsNeverLinked() = runTest {
        val result = LinkResolverImpl().resolve(
            tgItem("tguser987654321_42"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals("user", result.trace.source)
    }

    @Test
    fun secretChatIsNeverLinked() = runTest {
        val result = LinkResolverImpl().resolve(
            tgItem("tgenc55555_7"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals("encrypted", result.trace.source)
    }

    @Test
    fun aPrunedWearableBundleIsAnExplainableMiss() = runTest {
        // Some OEMs strip android.wearable.EXTENSIONS entirely — an expected outcome, not an error.
        val result = LinkResolverImpl().resolve(tgItem(null), disabledPackages = emptySet())
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
        assertEquals("none", result.trace.source)
    }

    @Test
    fun optedOutPackageSkipsResolution() = runTest {
        val result = LinkResolverImpl().resolve(
            tgItem("tgchat1234567890_4567"),
            disabledPackages = setOf("org.telegram.messenger"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
