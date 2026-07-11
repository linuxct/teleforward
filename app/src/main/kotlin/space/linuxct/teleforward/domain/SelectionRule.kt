package space.linuxct.teleforward.domain

/**
 * Whether a matching [SelectionRule] causes a notification to be forwarded or suppressed.
 *
 * - [INCLUDE]: notifications in this scope are forwarded.
 * - [EXCLUDE]: notifications in this scope are suppressed (used to carve an exception out of a
 *   broader whole-app INCLUDE — e.g. "forward all of app X except channel Y").
 */
enum class RuleMode {
    INCLUDE,
    EXCLUDE,
}

/**
 * A user selection decision for a scope. There are three scope kinds:
 *  - whole-app: `channelId == null && conversationId == null`.
 *  - channel:   `channelId != null && conversationId == null`.
 *  - conversation: `conversationId != null` (a specific chat, e.g. one WhatsApp conversation). The
 *    stored `channelId` is the channel the conversation lives on, but matching ignores it and keys
 *    on `packageName + conversationId`.
 *
 * Precedence (resolved by [FilterEngine]): an enabled conversation rule wins over an enabled
 * explicit channel rule, which wins over an enabled whole-app rule, which wins over the implicit
 * default (drop).
 *
 * @property packageName the app this rule applies to.
 * @property channelId the specific channel, or null for a whole-app rule.
 * @property conversationId the specific conversation shortcut id, or null for channel/whole-app rules.
 * @property enabled if false the rule is inert (treated as absent).
 * @property mode include/exclude behaviour when this rule is the winning match.
 */
data class SelectionRule(
    val packageName: String,
    val channelId: String?,
    val conversationId: String?,
    val enabled: Boolean,
    val mode: RuleMode,
)
