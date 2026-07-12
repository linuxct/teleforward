@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package space.linuxct.teleforward.ui.channels

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.teleforward.R
import space.linuxct.teleforward.data.link.MagicLinkKind
import space.linuxct.teleforward.data.link.magicLinkKind
import space.linuxct.teleforward.designsystem.AppScaffold
import space.linuxct.teleforward.designsystem.SectionHeader

/**
 * Per-app channel picker. Header shows the app name and a whole-app forwarding toggle; below, each
 * seen channel and each seen conversation has its own toggle. Precedence, highest first:
 * conversation > channel > whole-app.
 */
@Composable
fun ChannelPickerRoute(
    packageName: String,
    onBack: () -> Unit,
) {
    val viewModel: ChannelPickerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChannelPickerScreen(
        state = state,
        fallbackName = packageName,
        onBack = onBack,
        onToggleWholeApp = viewModel::onToggleWholeApp,
        onToggleChannel = viewModel::onToggleChannel,
        onToggleConversation = viewModel::onToggleConversation,
        onToggleMagicLink = viewModel::onToggleMagicLink,
    )
}

@Composable
private fun ChannelPickerScreen(
    state: ChannelPickerUiState,
    fallbackName: String,
    onBack: () -> Unit,
    onToggleWholeApp: (Boolean) -> Unit,
    onToggleChannel: (String, Boolean) -> Unit,
    onToggleConversation: (conversationId: String, channelId: String, enabled: Boolean) -> Unit,
    onToggleMagicLink: (Boolean) -> Unit,
) {
    val title = state.appLabel.ifBlank { fallbackName }
    AppScaffold(title = title, onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                WholeAppHeader(
                    appLabel = title,
                    enabled = state.wholeAppEnabled,
                    onToggle = onToggleWholeApp,
                )
            }
            if (state.magicLinkSupported) {
                item {
                    MagicLinkCard(
                        enabled = state.magicLinkEnabled,
                        onToggle = onToggleMagicLink,
                    )
                }
            }
            // WhatsApp-only: the opt-in Contacts affordance that upgrades saved-contact (`@lid`) chats
            // — which hide the phone number — into WhatsApp Web links. Shown only while magic link is on.
            if (state.magicLinkEnabled && magicLinkKind(state.packageName) == MagicLinkKind.WHATSAPP) {
                item { WhatsAppContactsCard() }
            }
            item { PrecedenceExplainer() }
            item { SectionHeader("Channels") }

            when {
                state.loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                state.channels.isEmpty() -> item { EmptyChannels() }

                else -> items(state.channels, key = { "channel:${it.channelId}" }) { channel ->
                    ChannelRow(
                        channel = channel,
                        onToggle = { onToggleChannel(channel.channelId, it) },
                    )
                }
            }

            if (!state.loading) {
                item { SectionHeader("Conversations") }
                if (state.conversations.isEmpty()) {
                    item { EmptyConversations() }
                } else {
                    items(state.conversations, key = { "conversation:${it.conversationId}" }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onToggle = {
                                onToggleConversation(
                                    conversation.conversationId,
                                    conversation.channelId,
                                    it,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MagicLinkCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_auto_awesome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Reconstruct magic link",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Best-effort: reconstructs the link this notification points to from the " +
                        "details it exposes and appends it to the forwarded message. Heuristic — it " +
                        "won't always find a match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun WhatsAppContactsCard() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted -> granted = isGranted }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Resolve saved contacts",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "WhatsApp hides phone numbers behind an internal ID, so chats with saved " +
                        "contacts can't be linked without Contacts access. Grant it to turn those into " +
                        "links — the number stays on your device except inside the link you forward.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Grant")
                }
            }
        }
    }
}

@Composable
private fun WholeAppHeader(
    appLabel: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Forward entire app",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "When on, every notification from $appLabel is forwarded unless a " +
                        "channel below overrides it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun PrecedenceExplainer() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Precedence: a conversation switch wins over its channel, and a channel " +
                    "switch wins over the whole-app setting — turn any of them on or off to override.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelRowUi,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = channel.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(channel.supportingText()) },
        trailingContent = {
            Switch(checked = channel.enabled, onCheckedChange = onToggle)
        },
    )
}

@Composable
private fun ConversationRow(
    conversation: ConversationRowUi,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(conversation.supportingText()) },
        trailingContent = {
            Switch(checked = conversation.enabled, onCheckedChange = onToggle)
        },
    )
}

@Composable
private fun EmptyConversations() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No conversations seen yet — receive a message from a specific chat to see it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyChannels() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No channels seen yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Trigger a notification from this app and it will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun ChannelRowUi.supportingText(): String = buildString {
    append(importanceLabel(importance))
    append(" · Last seen ")
    append(relativeLastSeen(lastSeen))
}

private fun ConversationRowUi.supportingText(): String = "Last seen ${relativeLastSeen(lastSeen)}"

private fun relativeLastSeen(lastSeen: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        lastSeen,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )

/** Maps a [android.app.NotificationManager] importance constant to a short human label. */
private fun importanceLabel(importance: Int?): String = when (importance) {
    null -> "Importance unknown"
    0 -> "Silent" // IMPORTANCE_NONE
    1 -> "Minimal" // IMPORTANCE_MIN
    2 -> "Low" // IMPORTANCE_LOW
    3 -> "Default" // IMPORTANCE_DEFAULT
    4 -> "High" // IMPORTANCE_HIGH
    5 -> "Urgent" // IMPORTANCE_MAX
    else -> "Importance unknown" // IMPORTANCE_UNSPECIFIED (-1000) or unexpected
}
