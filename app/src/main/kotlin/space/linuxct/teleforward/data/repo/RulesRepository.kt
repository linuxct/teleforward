package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.domain.RuleMode
import space.linuxct.teleforward.domain.SelectionRule

/**
 * Reads/writes the user's per-app and per-channel selection rules (domain [SelectionRule]s),
 * backed by the Room `selection_rules` table.
 */
interface RulesRepository {

    fun observeAllRules(): Flow<List<SelectionRule>>

    fun observeRulesForPackage(packageName: String): Flow<List<SelectionRule>>

    suspend fun getAllRules(): List<SelectionRule>

    suspend fun getRulesForPackage(packageName: String): List<SelectionRule>

    suspend fun getRule(
        packageName: String,
        channelId: String?,
        conversationId: String? = null,
    ): SelectionRule?

    /** Upsert a rule for its exact scope (packageName, channelId, conversationId). */
    suspend fun setRule(rule: SelectionRule)

    /** Convenience: upsert a specific-channel rule. */
    suspend fun setChannelRule(
        packageName: String,
        channelId: String,
        mode: RuleMode,
        enabled: Boolean,
    )

    /** Convenience: upsert the whole-app rule (channelId == null, conversationId == null). */
    suspend fun setWholeAppRule(packageName: String, mode: RuleMode, enabled: Boolean)

    /**
     * Convenience: upsert a per-conversation rule. [channelId] is the channel the conversation lives
     * on (stored for reference); matching keys on packageName + conversationId. Any existing rule for
     * the same conversation is replaced regardless of the channel it was previously stored under.
     */
    suspend fun setConversationRule(
        packageName: String,
        channelId: String?,
        conversationId: String,
        mode: RuleMode,
        enabled: Boolean,
    )

    suspend fun removeRule(packageName: String, channelId: String?)

    /** Remove a conversation rule by its chat identity (any channel). */
    suspend fun removeConversationRule(packageName: String, conversationId: String)

    suspend fun removeRulesForPackage(packageName: String)

    suspend fun clear()
}
