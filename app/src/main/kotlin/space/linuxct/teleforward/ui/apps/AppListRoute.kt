@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package space.linuxct.teleforward.ui.apps

import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.designsystem.AppScaffold

/**
 * App-selection list: a searchable, filterable list of apps. Each row has the app icon, label, a
 * "seen recently" hint, a whole-app forwarding [Switch], and drills down into the channel picker.
 * Top-bar actions open the delivery log and settings.
 */
@Composable
fun AppListRoute(
    onOpenChannels: (packageName: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val viewModel: AppListViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppListScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
        onToggleApp = viewModel::onToggleApp,
        onOpenChannels = onOpenChannels,
        onOpenSettings = onOpenSettings,
        onOpenLog = onOpenLog,
    )
}

@Composable
private fun AppListScreen(
    state: AppListUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onOpenChannels: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
) {
    AppScaffold(
        title = "Apps",
        actions = {
            IconButton(onClick = onOpenLog) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Delivery log")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )

            FilterRow(
                selected = state.filter,
                onSelect = onFilterChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                state.loading -> CenteredBox { CircularProgressIndicator() }

                state.apps.isEmpty() -> EmptyApps(
                    filtered = state.query.isNotBlank() || state.filter != AppFilter.ALL,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.apps, key = { it.packageName }) { row ->
                        AppRow(
                            row = row,
                            onToggle = { onToggleApp(row.packageName, it) },
                            onOpen = { onOpenChannels(row.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: AppFilter,
    onSelect: (AppFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = AppFilter.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(filter.displayName()) },
            )
        }
    }
}

@Composable
private fun AppRow(
    row: AppRowUi,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { AppIcon(packageName = row.packageName, label = row.label) },
        headlineContent = {
            Text(
                text = row.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(row.supportingText()) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = row.wholeAppEnabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open channels",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * App launcher icon, loaded off the main thread from [android.content.pm.PackageManager] and
 * rendered via Coil 3. Falls back to a letter avatar while loading or when the app is not installed.
 */
@Composable
private fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = icon
        if (current != null) {
            AsyncImage(
                model = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyApps(filtered: Boolean) {
    CenteredBox {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = if (filtered) "No apps match your search" else "No apps yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = if (filtered) {
                    "Try a different search or filter."
                } else {
                    "Apps appear here once a notification from them has been observed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun AppFilter.displayName(): String = when (this) {
    AppFilter.ALL -> "All"
    AppFilter.ENABLED -> "Enabled"
    AppFilter.SEEN -> "Seen"
}

private fun AppRowUi.supportingText(): String = buildString {
    val seenAt = lastSeen
    if (isSeen && seenAt != null) {
        append("Seen ")
        append(
            DateUtils.getRelativeTimeSpanString(
                seenAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ),
        )
    } else {
        append("Not seen yet")
    }
    if (enabledChannelCount > 0) {
        append(" · ")
        append(enabledChannelCount)
        append(if (enabledChannelCount == 1) " channel on" else " channels on")
    }
}
