package space.linuxct.teleforward.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {

    private val engine = FilterEngine()

    private fun notification(
        packageName: String = "com.example.app",
        channelId: String = "chat",
        conversationId: String? = null,
    ) = RawNotification(
        packageName = packageName,
        appLabel = "Example",
        channelId = channelId,
        channelName = "Chat",
        conversationId = conversationId,
        title = "Title",
        body = "Body",
        postTime = 0L,
        userSerial = 0L,
        imagePaths = emptyList(),
        key = "key",
        dedupeKey = "key",
    )

    private fun rule(
        packageName: String = "com.example.app",
        channelId: String? = null,
        conversationId: String? = null,
        enabled: Boolean = true,
        mode: RuleMode = RuleMode.INCLUDE,
    ) = SelectionRule(
        packageName = packageName,
        channelId = channelId,
        conversationId = conversationId,
        enabled = enabled,
        mode = mode,
    )

    @Test
    fun `global pause drops everything even with an include rule`() {
        val rules = listOf(rule(mode = RuleMode.INCLUDE))
        val decision = engine.decide(notification(), rules, forwardingEnabled = false)
        assertEquals(FilterDecision.DROP_PAUSED, decision)
        assertFalse(decision.forward)
    }

    @Test
    fun `no matching rule drops by default`() {
        val decision = engine.decide(notification(), rules = emptyList(), forwardingEnabled = true)
        assertEquals(FilterDecision.DROP_NO_MATCH, decision)
    }

    @Test
    fun `whole-app include forwards`() {
        val rules = listOf(rule(mode = RuleMode.INCLUDE))
        assertTrue(engine.shouldForward(notification(), rules, forwardingEnabled = true))
    }

    @Test
    fun `explicit channel exclude overrides whole-app include`() {
        val rules = listOf(
            rule(channelId = null, mode = RuleMode.INCLUDE),
            rule(channelId = "chat", mode = RuleMode.EXCLUDE),
        )
        val decision = engine.decide(notification(channelId = "chat"), rules, forwardingEnabled = true)
        assertEquals(FilterDecision.DROP_EXCLUDED, decision)
    }

    @Test
    fun `explicit channel include overrides whole-app exclude`() {
        val rules = listOf(
            rule(channelId = null, mode = RuleMode.EXCLUDE),
            rule(channelId = "vip", mode = RuleMode.INCLUDE),
        )
        val decision = engine.decide(notification(channelId = "vip"), rules, forwardingEnabled = true)
        assertEquals(FilterDecision.FORWARD, decision)
    }

    @Test
    fun `disabled rule is ignored and falls through to default drop`() {
        val rules = listOf(rule(channelId = null, enabled = false, mode = RuleMode.INCLUDE))
        val decision = engine.decide(notification(), rules, forwardingEnabled = true)
        assertEquals(FilterDecision.DROP_NO_MATCH, decision)
    }

    @Test
    fun `rule for another package does not match`() {
        val rules = listOf(rule(packageName = "com.other.app", mode = RuleMode.INCLUDE))
        val decision = engine.decide(
            notification(packageName = "com.example.app"),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.DROP_NO_MATCH, decision)
    }

    @Test
    fun `channel without explicit rule falls back to whole-app rule`() {
        val rules = listOf(
            rule(channelId = null, mode = RuleMode.INCLUDE),
            rule(channelId = "muted", mode = RuleMode.EXCLUDE),
        )
        // A different channel with no explicit rule uses the whole-app INCLUDE.
        val decision = engine.decide(notification(channelId = "other"), rules, forwardingEnabled = true)
        assertEquals(FilterDecision.FORWARD, decision)
    }

    // --- conversation-level rules --------------------------------------------------------------

    @Test
    fun `conversation include forwards when app and channel are off`() {
        // No whole-app or channel rule at all — only the conversation is selected.
        val rules = listOf(
            rule(channelId = "messages", conversationId = "chat-42", mode = RuleMode.INCLUDE),
        )
        val decision = engine.decide(
            notification(channelId = "messages", conversationId = "chat-42"),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.FORWARD, decision)
    }

    @Test
    fun `conversation include overrides a whole-app exclude`() {
        val rules = listOf(
            rule(channelId = null, mode = RuleMode.EXCLUDE),
            rule(channelId = "messages", conversationId = "chat-42", mode = RuleMode.INCLUDE),
        )
        val decision = engine.decide(
            notification(channelId = "messages", conversationId = "chat-42"),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.FORWARD, decision)
    }

    @Test
    fun `conversation exclude carves a single chat out of a channel include`() {
        val rules = listOf(
            rule(channelId = "messages", mode = RuleMode.INCLUDE),
            rule(channelId = "messages", conversationId = "spam", mode = RuleMode.EXCLUDE),
        )
        // The excluded chat is dropped...
        assertEquals(
            FilterDecision.DROP_EXCLUDED,
            engine.decide(
                notification(channelId = "messages", conversationId = "spam"),
                rules,
                forwardingEnabled = true,
            ),
        )
        // ...while other chats on the same channel still forward via the channel INCLUDE.
        assertEquals(
            FilterDecision.FORWARD,
            engine.decide(
                notification(channelId = "messages", conversationId = "friend"),
                rules,
                forwardingEnabled = true,
            ),
        )
    }

    @Test
    fun `conversation rule matches by id regardless of the rule channelId`() {
        // Rule stored under a stale channelId still matches the conversation on a different channel.
        val rules = listOf(
            rule(channelId = "old-channel", conversationId = "chat-42", mode = RuleMode.INCLUDE),
        )
        val decision = engine.decide(
            notification(channelId = "new-channel", conversationId = "chat-42"),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.FORWARD, decision)
    }

    @Test
    fun `null conversationId falls back to the channel rule`() {
        val rules = listOf(
            rule(channelId = "messages", mode = RuleMode.INCLUDE),
            rule(channelId = "messages", conversationId = "chat-42", mode = RuleMode.EXCLUDE),
        )
        // A non-conversation notification on the channel ignores the conversation EXCLUDE.
        val decision = engine.decide(
            notification(channelId = "messages", conversationId = null),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.FORWARD, decision)
    }

    @Test
    fun `disabled conversation rule falls back to the channel rule`() {
        val rules = listOf(
            rule(channelId = "messages", mode = RuleMode.INCLUDE),
            rule(
                channelId = "messages",
                conversationId = "chat-42",
                enabled = false,
                mode = RuleMode.EXCLUDE,
            ),
        )
        val decision = engine.decide(
            notification(channelId = "messages", conversationId = "chat-42"),
            rules,
            forwardingEnabled = true,
        )
        assertEquals(FilterDecision.FORWARD, decision)
    }
}
