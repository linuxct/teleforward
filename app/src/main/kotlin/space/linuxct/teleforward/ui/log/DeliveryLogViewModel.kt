package space.linuxct.teleforward.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.work.DeliveryWorker
import javax.inject.Inject

/**
 * A single row of the delivery log, projected from an [OutboxWithImages] into the metadata the UI
 * renders (app/channel/title/body + status/time/image-count). Kept UI-facing so the composables
 * stay free of Room types.
 */
data class LogRow(
    val id: Long,
    val appLabel: String,
    val channelName: String?,
    val title: String?,
    val body: String?,
    val status: OutboxStatus,
    val postTime: Long,
    val imageCount: Int,
    val lastError: String?,
) {
    /** FAILED/EXPIRED rows can be made deliverable again from the log. */
    val canRetry: Boolean
        get() = status == OutboxStatus.FAILED || status == OutboxStatus.EXPIRED
}

/**
 * UI state for the delivery-log screen. A single immutable snapshot exposed as one [StateFlow].
 */
data class LogUiState(
    val loading: Boolean = true,
    val rows: List<LogRow> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
}

/**
 * Backs [DeliveryLogRoute]. Observes the most recent outbox rows and exposes retry (reschedule +
 * drain-worker enqueue) and clear-sent actions.
 */
@HiltViewModel
class DeliveryLogViewModel @Inject constructor(
    private val outboxRepository: OutboxRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    val uiState: StateFlow<LogUiState> =
        outboxRepository.observeRecent(RECENT_LIMIT)
            .map { rows -> LogUiState(loading = false, rows = rows.map(OutboxWithImages::toLogRow)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = LogUiState(loading = true),
            )

    /**
     * Make a FAILED/EXPIRED item deliverable again (reset attempts, due now, clear error) and kick
     * the unique drain worker so it is picked up promptly.
     */
    fun retry(id: Long) {
        viewModelScope.launch {
            outboxRepository.reschedule(
                id = id,
                attemptCount = 0,
                nextAttemptAt = System.currentTimeMillis(),
                error = null,
            )
            enqueueDrain()
        }
    }

    /** Remove delivered (SENT) rows from the log. */
    fun clearSent() {
        viewModelScope.launch { outboxRepository.clearSent() }
    }

    private fun enqueueDrain() {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(DRAIN_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val RECENT_LIMIT = 100
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Unique name for the outbox drain worker, per the delivery-log spec. NOTE: this differs
         * from [DeliveryWorker.UNIQUE_WORK_NAME] ("outbox_drain") — see the agent report.
         */
        private const val DRAIN_WORK_NAME = "teleforward_outbox_drain"
    }
}

private fun OutboxWithImages.toLogRow(): LogRow = LogRow(
    id = outbox.id,
    appLabel = outbox.appLabel,
    channelName = outbox.channelName,
    title = outbox.title,
    body = outbox.body,
    status = outbox.status,
    postTime = outbox.postTime,
    imageCount = images.size,
    lastError = outbox.lastError,
)
