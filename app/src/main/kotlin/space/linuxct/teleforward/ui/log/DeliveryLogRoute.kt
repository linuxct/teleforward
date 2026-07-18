package space.linuxct.teleforward.ui.log

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.teleforward.R
import space.linuxct.teleforward.data.db.entity.OutboxStatus
import space.linuxct.teleforward.designsystem.AppScaffold

/**
 * Delivery log (Wave 2): the most recent outbox items with a status chip, image-count badge and a
 * retry action for FAILED/EXPIRED rows. Metadata-forward — app/channel and title lead each row.
 *
 * Frozen entry signature. The Hilt view-model is resolved inside the body so the public signature
 * stays exactly `DeliveryLogRoute(onBack: () -> Unit)`.
 */
@Composable
fun DeliveryLogRoute(onBack: () -> Unit) {
    val viewModel: DeliveryLogViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DeliveryLogScreen(
        state = state,
        onBack = onBack,
        onClearSent = viewModel::clearSent,
        onRetry = viewModel::retry,
    )
}

@Composable
private fun DeliveryLogScreen(
    state: LogUiState,
    onBack: () -> Unit,
    onClearSent: () -> Unit,
    onRetry: (Long) -> Unit,
) {
    val hasSent = state.rows.any { it.status == OutboxStatus.SENT }
    AppScaffold(
        title = stringResource(R.string.log_title),
        onBack = onBack,
        actions = {
            TextButton(onClick = onClearSent, enabled = hasSent) {
                Text(stringResource(R.string.log_action_clear))
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(padding)
            state.isEmpty -> EmptyState(padding)
            else -> LogList(rows = state.rows, contentPadding = padding, onRetry = onRetry)
        }
    }
}

@Composable
private fun LoadingState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.log_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.log_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogList(
    rows: List<LogRow>,
    contentPadding: PaddingValues,
    onRetry: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = rows, key = { it.id }) { row ->
            LogRowCard(row = row, onRetry = onRetry)
        }
    }
}

@Composable
private fun LogRowCard(
    row: LogRow,
    onRetry: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.channelName
                            ?.let { stringResource(R.string.log_app_channel, row.appLabel, it) }
                            ?: row.appLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    row.body?.takeIf { it.isNotBlank() }?.let { body ->
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusChip(status = row.status)
            }

            Spacer(Modifier.size(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativeTime(row.postTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.imageCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    ImageCountBadge(count = row.imageCount)
                }
                Spacer(Modifier.weight(1f))
                if (row.canRetry) {
                    FilledTonalButton(onClick = { onRetry(row.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.log_action_retry))
                    }
                }
            }

            if (row.status == OutboxStatus.FAILED) {
                row.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A distinctly-coloured pill conveying the item's delivery status. */
@Composable
private fun StatusChip(status: OutboxStatus) {
    val colors = statusChipColors(status)
    Surface(
        color = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** A compact tonal badge showing how many images ride along with the item. */
@Composable
private fun ImageCountBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = pluralStringResource(R.plurals.log_image_count, count, count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@StringRes
private fun OutboxStatus.labelRes(): Int = when (this) {
    OutboxStatus.PENDING -> R.string.log_status_pending
    OutboxStatus.SENDING -> R.string.log_status_sending
    OutboxStatus.SENT -> R.string.log_status_sent
    OutboxStatus.FAILED -> R.string.log_status_failed
    OutboxStatus.EXPIRED -> R.string.log_status_expired
}

private data class ChipColors(val container: Color, val content: Color)

@Composable
private fun statusChipColors(status: OutboxStatus): ChipColors {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return when (status) {
        OutboxStatus.PENDING -> ChipColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        OutboxStatus.SENDING -> ChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        OutboxStatus.SENT ->
            if (dark) {
                ChipColors(Color(0xFF1B3B24), Color(0xFFA6F0B4))
            } else {
                ChipColors(Color(0xFFCDEFD1), Color(0xFF0B5A2A))
            }
        OutboxStatus.FAILED -> ChipColors(scheme.errorContainer, scheme.onErrorContainer)
        OutboxStatus.EXPIRED -> ChipColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

private fun relativeTime(postTime: Long): String =
    DateUtils.getRelativeTimeSpanString(
        postTime,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
