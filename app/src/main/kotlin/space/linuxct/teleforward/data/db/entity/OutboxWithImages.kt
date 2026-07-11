package space.linuxct.teleforward.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * An outbox row together with its attached images, used by the sender/worker to build and deliver
 * a message in one read.
 */
data class OutboxWithImages(
    @Embedded val outbox: OutboxEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "outboxId",
    )
    val images: List<OutboxImageEntity>,
)
