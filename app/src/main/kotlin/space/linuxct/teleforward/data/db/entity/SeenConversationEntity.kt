package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity

/**
 * Catalog of conversations (individual chats) discovered from observed notifications. Composite
 * primary key `(packageName, channelId, conversationId, userSerial)` — the same conversation id can
 * legitimately appear under different channels or user profiles.
 */
@Entity(
    tableName = "seen_conversations",
    primaryKeys = ["packageName", "channelId", "conversationId", "userSerial"],
)
data class SeenConversationEntity(
    val packageName: String,
    val channelId: String,
    val conversationId: String,
    val title: String?,
    val userSerial: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)
