package space.linuxct.teleforward.data.link

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * Tests the Discord branch of [LinkResolverImpl.resolve]: a 1:1 DM resolves to a
 * `discord.com/channels/@me/…` url from the conversation shortcut (the channel snowflake) plus the
 * `latestMessageId` extra, while a server channel — whose guild id no readable field carries —
 * deliberately resolves to nothing. Pure: no Android, no network.
 *
 * The snowflakes are synthetic placeholders shaped like real Discord ids.
 */
class LinkResolverDiscordTest {

    private val channelId = "1238004201456406590"
    private val messageId = "1479209639156519166"

    private fun discordItem(
        conversationId: String? = null,
        isGroupConversation: Boolean? = null,
        discordMessageId: String? = null,
    ) = OutboxEntity(
        dedupeKey = "k",
        packageName = "com.discord",
        channelId = "messages",
        appLabel = "Discord",
        channelName = null,
        title = "Someone",
        body = "hi",
        conversationId = conversationId,
        isGroupConversation = isGroupConversation,
        discordMessageId = discordMessageId,
        postTime = 0L,
        status = OutboxStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 0L,
    )

    @Test
    fun dmWithMessageIdResolvesToMessageUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            discordItem(
                conversationId = channelId,
                isGroupConversation = false,
                discordMessageId = messageId,
            ),
            disabledPackages = emptySet(),
        )
        assertEquals("https://discord.com/channels/@me/$channelId/$messageId", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
        assertEquals(Discord.SERVICE, result.trace.service)
    }

    @Test
    fun dmWithoutMessageIdStillResolvesToChannelUrl() = runTest {
        val result = LinkResolverImpl().resolve(
            discordItem(conversationId = channelId, isGroupConversation = false),
            disabledPackages = emptySet(),
        )
        assertEquals("https://discord.com/channels/@me/$channelId", result.url)
        assertEquals(MagicLinkOutcome.MATCHED, result.trace.outcome)
    }

    @Test
    fun serverChannelIsNeverLinked() = runTest {
        // isGroupConversation = true -> a guild channel/thread/group DM. Its canonical url needs the
        // guild id, which is unreadable, so we must emit NOTHING rather than a bogus `@me` url.
        val result = LinkResolverImpl().resolve(
            discordItem(
                conversationId = channelId,
                isGroupConversation = true,
                discordMessageId = messageId,
            ),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun unknownConversationKindIsNeverLinked() = runTest {
        // No MessagingStyle -> null. Must NOT be treated as a DM (a pre-upgrade row, or a server
        // notification that carried no flag, would otherwise get a wrong-channel url).
        val result = LinkResolverImpl().resolve(
            discordItem(conversationId = channelId, isGroupConversation = null),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun nonSnowflakeConversationIdIsNeverLinked() = runTest {
        val result = LinkResolverImpl().resolve(
            discordItem(conversationId = "t:Some Chat", isGroupConversation = false),
            disabledPackages = emptySet(),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.NO_MATCH, result.trace.outcome)
    }

    @Test
    fun optedOutPackageSkipsResolution() = runTest {
        val result = LinkResolverImpl().resolve(
            discordItem(
                conversationId = channelId,
                isGroupConversation = false,
                discordMessageId = messageId,
            ),
            disabledPackages = setOf("com.discord"),
        )
        assertNull(result.url)
        assertEquals(MagicLinkOutcome.SKIPPED_DISABLED, result.trace.outcome)
    }
}
