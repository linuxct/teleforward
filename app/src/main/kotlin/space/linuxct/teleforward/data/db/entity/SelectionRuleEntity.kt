package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import space.linuxct.teleforward.domain.RuleMode

/**
 * Persisted selection rule. Scope kinds: whole-app (`channelId == null && conversationId == null`),
 * channel (`channelId != null && conversationId == null`), conversation (`conversationId != null`).
 *
 * The unique index on `(packageName, channelId, conversationId)` enforces at most one rule per
 * scope. Note that, per SQLite semantics, NULLs are distinct in unique indexes, so rows with a null
 * channelId/conversationId are not uniquely constrained at the DB level; the repository upserts by
 * delete-then-insert on the exact scope.
 */
@Entity(
    tableName = "selection_rules",
    indices = [Index(value = ["packageName", "channelId", "conversationId"], unique = true)],
)
data class SelectionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val channelId: String?,
    val conversationId: String?,
    val enabled: Boolean,
    val mode: RuleMode,
    val updatedAt: Long,
)
