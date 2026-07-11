package space.linuxct.teleforward.domain

import javax.inject.Inject

/**
 * Outcome of evaluating a notification against the current rule set, carrying the reason so the
 * caller can log/telemeter why a notification was or was not forwarded.
 */
enum class FilterDecision(val forward: Boolean) {
    /** A winning INCLUDE rule matched — forward. */
    FORWARD(true),

    /** Global forwarding is paused — nothing is forwarded regardless of rules. */
    DROP_PAUSED(false),

    /** A winning EXCLUDE rule matched — explicitly suppressed. */
    DROP_EXCLUDED(false),

    /** No rule selected this notification's channel or app — dropped by default. */
    DROP_NO_MATCH(false),
}

/**
 * Pure-Kotlin (no Android imports) decision core. Deterministic and fully unit-testable.
 *
 * Precedence, highest first:
 *  1. Global pause: if `forwardingEnabled == false` → [FilterDecision.DROP_PAUSED].
 *  2. Conversation rule: an enabled rule with `packageName == notification.packageName`,
 *     `conversationId != null` and `conversationId == notification.conversationId` →
 *     INCLUDE ⇒ FORWARD, EXCLUDE ⇒ DROP_EXCLUDED. Matching keys on `packageName + conversationId`
 *     and ignores the rule's `channelId`. A conversation INCLUDE therefore overrides an app/channel
 *     that's off, and a conversation EXCLUDE carves a single chat out of a channel INCLUDE.
 *  3. Explicit channel rule: an enabled rule with matching package, `conversationId == null` and
 *     `channelId == notification.channelId` → INCLUDE ⇒ FORWARD, EXCLUDE ⇒ DROP_EXCLUDED.
 *  4. Whole-app rule: an enabled rule with matching package, `channelId == null` and
 *     `conversationId == null` → INCLUDE ⇒ FORWARD, EXCLUDE ⇒ DROP_EXCLUDED.
 *  5. Otherwise → [FilterDecision.DROP_NO_MATCH].
 *
 * A notification with a null [RawNotification.conversationId] can never match a conversation rule,
 * so it falls through to the channel/whole-app rules.
 *
 * Disabled rules ([SelectionRule.enabled] == false) are ignored as if absent.
 */
class FilterEngine @Inject constructor() {

    /** Full decision including the reason. */
    fun decide(
        notification: RawNotification,
        rules: List<SelectionRule>,
        forwardingEnabled: Boolean,
    ): FilterDecision {
        if (!forwardingEnabled) return FilterDecision.DROP_PAUSED

        val enabledForPkg = rules.asSequence()
            .filter { it.enabled && it.packageName == notification.packageName }

        // 2. Conversation rule wins over channel and whole-app rules. Matches on pkg + conversationId
        //    (channelId ignored); only possible when the notification carries a conversationId.
        val conversationRule = notification.conversationId?.let { conversationId ->
            enabledForPkg.firstOrNull { it.conversationId == conversationId }
        }
        if (conversationRule != null) {
            return conversationRule.toDecision()
        }

        // 3. Explicit channel rule wins over the whole-app rule.
        val channelRule = enabledForPkg.firstOrNull {
            it.conversationId == null && it.channelId == notification.channelId
        }
        if (channelRule != null) {
            return channelRule.toDecision()
        }

        // 4. Whole-app rule (channelId == null && conversationId == null).
        val wholeAppRule = enabledForPkg.firstOrNull {
            it.channelId == null && it.conversationId == null
        }
        if (wholeAppRule != null) {
            return wholeAppRule.toDecision()
        }

        // 5. Nothing selected this notification.
        return FilterDecision.DROP_NO_MATCH
    }

    private fun SelectionRule.toDecision(): FilterDecision =
        if (mode == RuleMode.INCLUDE) FilterDecision.FORWARD else FilterDecision.DROP_EXCLUDED

    /** Convenience boolean: true only when [decide] resolves to [FilterDecision.FORWARD]. */
    fun shouldForward(
        notification: RawNotification,
        rules: List<SelectionRule>,
        forwardingEnabled: Boolean,
    ): Boolean = decide(notification, rules, forwardingEnabled).forward
}
