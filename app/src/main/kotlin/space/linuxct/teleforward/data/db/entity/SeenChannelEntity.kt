package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity

/**
 * Catalog of notification channels discovered from observed notifications. Composite primary key
 * `(packageName, channelId, userSerial)` — the same channel id can legitimately appear under
 * different user profiles.
 */
@Entity(
    tableName = "seen_channels",
    primaryKeys = ["packageName", "channelId", "userSerial"],
)
data class SeenChannelEntity(
    val packageName: String,
    val channelId: String,
    val name: String?,
    val importance: Int?,
    val userSerial: Long,
    val firstSeen: Long,
    val lastSeen: Long,
)
