package space.linuxct.teleforward.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Kind/source of an extracted image, informing the send strategy and ordering.
 */
enum class OutboxImageKind {
    /** From Notification EXTRA_PICTURE (BigPictureStyle) — the primary image. */
    BIG_PICTURE,

    /** From getLargeIcon() — secondary/avatar image. */
    LARGE_ICON,

    /** Any other extracted bitmap. */
    OTHER,
}

/**
 * An image file (already persisted to app-private cache) belonging to an outbox item. Rows cascade
 * on delete of the parent outbox row; the delivery pipeline also deletes the underlying files on
 * SENT/EXPIRED.
 */
@Entity(
    tableName = "outbox_images",
    foreignKeys = [
        ForeignKey(
            entity = OutboxEntity::class,
            parentColumns = ["id"],
            childColumns = ["outboxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["outboxId"])],
)
data class OutboxImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val outboxId: Long,
    val filePath: String,
    val mime: String,
    val sizeBytes: Long,
    val kind: OutboxImageKind,
)
