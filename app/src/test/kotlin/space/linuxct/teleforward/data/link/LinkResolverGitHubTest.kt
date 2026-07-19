package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the GitHub branch of [LinkResolverImpl.resolve] — in particular that the reference is found
 * across the **title + body** join, not just the title. Pure: no Android, no network.
 */
class LinkResolverGitHubTest {

    private fun ghItem(title: String? = null, body: String? = null) = OutboxEntity(
        dedupeKey = "k",
        packageName = "com.github.android",
        channelId = "default",
        appLabel = "GitHub",
        channelName = null,
        title = title,
        body = body,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun findsTheReferenceInTheTitle() = runTest {
        val result = LinkResolverImpl().resolve(
            ghItem(title = "linuxct/teleforward#42", body = "someone commented"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://github.com/linuxct/teleforward/issues/42", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(GitHub.SERVICE, result.trace.service)
    }

    @Test
    fun findsTheReferenceInTheBody() = runTest {
        // The resolver joins title + body, so a reference in either half must be found.
        val result = LinkResolverImpl().resolve(
            ghItem(title = "New comment", body = "on microsoft/vscode#200000"),
            disabledPackages = emptySet(),
        )
        assertEquals("https://github.com/microsoft/vscode/issues/200000", result.url)
    }

    @Test
    fun aDiscussionIsNeverLinked() = runTest {
        // Discussions have their own numbering, so /issues/<n> could hit an unrelated issue.
        val result = LinkResolverImpl().resolve(
            ghItem(title = "New discussion", body = "linuxct/teleforward#12"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun notificationWithoutAReferenceIsNotLinked() = runTest {
        val result = LinkResolverImpl().resolve(
            ghItem(title = "Your build succeeded", body = "All checks passed"),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun optedOutPackageSkipsResolution() = runTest {
        val result = LinkResolverImpl().resolve(
            ghItem(title = "linuxct/teleforward#42"),
            disabledPackages = setOf("com.github.android"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
