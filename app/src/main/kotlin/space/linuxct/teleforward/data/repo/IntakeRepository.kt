package space.linuxct.teleforward.data.repo

import space.linuxct.teleforward.domain.RawNotification

/**
 * Outcome of enqueuing a [RawNotification] into the outbox.
 */
sealed interface IntakeResult {
    /** A new outbox row was created and the drain worker enqueued. */
    data class Enqueued(val outboxId: Long) : IntakeResult

    /** The notification's dedupeKey already existed — dropped. */
    data object Duplicate : IntakeResult
}

/**
 * The single write path from the notification listener into the delivery pipeline: persist a
 * deduped outbox row (+ image rows) then kick the unique drain worker
 * (`enqueueUniqueWork("outbox_drain", KEEP, DeliveryWorker)`), with constraints derived from
 * settings (network type / wifi-only).
 */
interface IntakeRepository {

    suspend fun enqueue(notification: RawNotification): IntakeResult
}
