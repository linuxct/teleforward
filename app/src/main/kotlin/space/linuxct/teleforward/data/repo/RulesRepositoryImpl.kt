package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.linuxct.teleforward.data.db.dao.RulesDao
import space.linuxct.teleforward.data.db.entity.SelectionRuleEntity
import space.linuxct.teleforward.domain.RuleMode
import space.linuxct.teleforward.domain.SelectionRule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [RulesRepository]. Maps [SelectionRuleEntity] <-> [SelectionRule] and upserts each
 * rule by delete-then-insert on its `(packageName, channelId)` scope. Delete-then-insert is
 * required for whole-app rules (`channelId == null`), which SQLite does not uniquely constrain
 * (NULLs are distinct in the unique index); doing it uniformly keeps a single row per scope.
 */
@Singleton
class RulesRepositoryImpl @Inject constructor(
    private val rulesDao: RulesDao,
) : RulesRepository {

    override fun observeAllRules(): Flow<List<SelectionRule>> =
        rulesDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeRulesForPackage(packageName: String): Flow<List<SelectionRule>> =
        rulesDao.observeForPackage(packageName).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAllRules(): List<SelectionRule> =
        rulesDao.getAll().map { it.toDomain() }

    override suspend fun getRulesForPackage(packageName: String): List<SelectionRule> =
        rulesDao.getForPackage(packageName).map { it.toDomain() }

    override suspend fun getRule(
        packageName: String,
        channelId: String?,
        conversationId: String?,
    ): SelectionRule? =
        rulesDao.getRule(packageName, channelId, conversationId)?.toDomain()

    override suspend fun setRule(rule: SelectionRule) {
        // Delete-then-insert on the exact scope so every rule kind stays unique.
        rulesDao.deleteRule(rule.packageName, rule.channelId, rule.conversationId)
        rulesDao.upsert(rule.toEntity(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setChannelRule(
        packageName: String,
        channelId: String,
        mode: RuleMode,
        enabled: Boolean,
    ) = setRule(
        SelectionRule(
            packageName = packageName,
            channelId = channelId,
            conversationId = null,
            enabled = enabled,
            mode = mode,
        ),
    )

    override suspend fun setWholeAppRule(packageName: String, mode: RuleMode, enabled: Boolean) =
        setRule(
            SelectionRule(
                packageName = packageName,
                channelId = null,
                conversationId = null,
                enabled = enabled,
                mode = mode,
            ),
        )

    override suspend fun setConversationRule(
        packageName: String,
        channelId: String?,
        conversationId: String,
        mode: RuleMode,
        enabled: Boolean,
    ) {
        // Remove any prior rule for this conversation (regardless of the channel it was stored on),
        // then insert the current one so a single conversation never yields duplicate rows.
        rulesDao.deleteConversationRule(packageName, conversationId)
        rulesDao.upsert(
            SelectionRule(
                packageName = packageName,
                channelId = channelId,
                conversationId = conversationId,
                enabled = enabled,
                mode = mode,
            ).toEntity(updatedAt = System.currentTimeMillis()),
        )
    }

    override suspend fun removeRule(packageName: String, channelId: String?) =
        rulesDao.deleteRule(packageName, channelId, conversationId = null)

    override suspend fun removeConversationRule(packageName: String, conversationId: String) =
        rulesDao.deleteConversationRule(packageName, conversationId)

    override suspend fun removeRulesForPackage(packageName: String) =
        rulesDao.deleteForPackage(packageName)

    override suspend fun clear() = rulesDao.clear()

    private fun SelectionRuleEntity.toDomain(): SelectionRule = SelectionRule(
        packageName = packageName,
        channelId = channelId,
        conversationId = conversationId,
        enabled = enabled,
        mode = mode,
    )

    private fun SelectionRule.toEntity(updatedAt: Long): SelectionRuleEntity = SelectionRuleEntity(
        packageName = packageName,
        channelId = channelId,
        conversationId = conversationId,
        enabled = enabled,
        mode = mode,
        updatedAt = updatedAt,
    )
}
