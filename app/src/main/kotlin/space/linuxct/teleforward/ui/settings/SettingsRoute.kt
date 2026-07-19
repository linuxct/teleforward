package space.linuxct.teleforward.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import space.linuxct.teleforward.BuildConfig
import space.linuxct.teleforward.R
import space.linuxct.teleforward.designsystem.AppScaffold
import space.linuxct.teleforward.designsystem.SectionHeader
import space.linuxct.teleforward.service.TelegramListenerService
import space.linuxct.teleforward.util.NotificationAccess
import kotlin.math.roundToInt

/**
 * Settings screen (plan "Compose UI — Settings"): bot-token enter/replace, recipient re-pair /
 * manual chat_id / send-test / clear, forwarding toggles + outbox expiry, and maintenance
 * (delivery-log cleanup, listener-health, battery-optimization). Frozen entry signature.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Security: this screen shows the bot token + recipient, so mark the window secure (blocks
    // screenshots / screen-recording / recents preview) while visible, and clear it on leave.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Re-check the notification listener each time the screen resumes — the user may have just
    // toggled the special access grant in the system Settings app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshListenerHealth()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One-shot diagnostics share intents: the VM builds the dump and emits the chooser Intent here;
    // we fire it from the UI so FLAG_SECURE / activity-launch stays on the composition side.
    LaunchedEffect(Unit) {
        viewModel.shareIntents.collect { intent -> context.launchSafely(intent) }
    }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onSubmitToken = viewModel::submitToken,
        onCapturePairing = viewModel::capturePairing,
        onSubmitManualChatId = viewModel::submitManualChatId,
        onSendTest = viewModel::sendTest,
        onClearPairing = viewModel::clearPairing,
        onSetPaused = viewModel::setPaused,
        onSetIncludeImages = viewModel::setIncludeImages,
        onSetIncludeAvatars = viewModel::setIncludeAvatars,
        onSetWifiOnly = viewModel::setWifiOnly,
        onSetSkipOngoing = viewModel::setSkipOngoing,
        onSetExpiryHours = viewModel::setOutboxExpiryHours,
        onClearDelivered = viewModel::clearDeliveredLog,
        onClearAll = viewModel::clearAllLog,
        onCheckForUpdates = viewModel::onCheckForUpdates,
        onSetDiagnosticsEnabled = viewModel::setDiagnosticsEnabled,
        // The always-on poller is a foreground service, which Android only lets us start from the
        // foreground — so it is started/stopped here, on the user's tap, rather than by the VM.
        onSetRemoteActionsEnabled = { enabled ->
            viewModel.setRemoteActionsEnabled(enabled)
            if (!enabled) TelegramListenerService.stop(context)
        },
        onSetRemoteActionsAlwaysOn = { enabled ->
            viewModel.setRemoteActionsAlwaysOn(enabled)
            if (enabled) {
                TelegramListenerService.start(context)
            } else {
                TelegramListenerService.stop(context)
            }
        },
        onSetNowPlayingEnabled = viewModel::setNowPlayingEnabled,
        onSetNowPlayingSongLink = viewModel::setNowPlayingSongLink,
        onDumpDiagnostics = viewModel::dumpDiagnostics,
        onClearDiagnostics = viewModel::clearDiagnostics,
    )
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSubmitToken: (String) -> Unit,
    onCapturePairing: () -> Unit,
    onSubmitManualChatId: (String) -> Unit,
    onSendTest: () -> Unit,
    onClearPairing: () -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onSetIncludeImages: (Boolean) -> Unit,
    onSetIncludeAvatars: (Boolean) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetSkipOngoing: (Boolean) -> Unit,
    onSetExpiryHours: (Int) -> Unit,
    onClearDelivered: () -> Unit,
    onClearAll: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onSetDiagnosticsEnabled: (Boolean) -> Unit,
    onSetRemoteActionsEnabled: (Boolean) -> Unit,
    onSetRemoteActionsAlwaysOn: (Boolean) -> Unit,
    onSetNowPlayingEnabled: (Boolean) -> Unit,
    onSetNowPlayingSongLink: (Boolean) -> Unit,
    onDumpDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
) {
    AppScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_bot_token))
            BotTokenCard(state = state, onSubmitToken = onSubmitToken)

            SectionHeader(stringResource(R.string.settings_section_recipient))
            RecipientCard(
                state = state,
                onCapturePairing = onCapturePairing,
                onSubmitManualChatId = onSubmitManualChatId,
                onSendTest = onSendTest,
                onClearPairing = onClearPairing,
            )

            SectionHeader(stringResource(R.string.settings_section_forwarding))
            ForwardingCard(
                state = state,
                onSetPaused = onSetPaused,
                onSetIncludeImages = onSetIncludeImages,
                onSetIncludeAvatars = onSetIncludeAvatars,
                onSetWifiOnly = onSetWifiOnly,
                onSetSkipOngoing = onSetSkipOngoing,
                onSetExpiryHours = onSetExpiryHours,
            )

            SectionHeader(stringResource(R.string.settings_section_remote_actions))
            RemoteActionsCard(
                state = state,
                onSetRemoteActionsEnabled = onSetRemoteActionsEnabled,
                onSetRemoteActionsAlwaysOn = onSetRemoteActionsAlwaysOn,
                onSetNowPlayingEnabled = onSetNowPlayingEnabled,
                onSetNowPlayingSongLink = onSetNowPlayingSongLink,
            )

            SectionHeader(stringResource(R.string.settings_section_maintenance))
            MaintenanceCard(
                state = state,
                onClearDelivered = onClearDelivered,
                onClearAll = onClearAll,
            )

            SectionHeader(stringResource(R.string.settings_section_about))
            AboutCard(
                state = state,
                onCheckForUpdates = onCheckForUpdates,
            )

            SectionHeader(stringResource(R.string.settings_section_diagnostics))
            DiagnosticsCard(
                state = state,
                onSetDiagnosticsEnabled = onSetDiagnosticsEnabled,
                onDumpDiagnostics = onDumpDiagnostics,
                onClearDiagnostics = onClearDiagnostics,
            )
        }
    }
}

// region Bot token

@Composable
private fun BotTokenCard(
    state: SettingsUiState,
    onSubmitToken: (String) -> Unit,
) {
    SettingsCard {
        val botUsername = state.botUsername
        val presence = when {
            !state.tokenSet -> stringResource(R.string.settings_token_none)
            !botUsername.isNullOrBlank() ->
                stringResource(R.string.settings_token_saved_as, botUsername)

            else -> stringResource(R.string.settings_token_saved)
        }
        StatusLine(
            ok = state.tokenSet,
            text = presence,
            okText = stringResource(R.string.settings_token_status_ok),
            problemText = stringResource(R.string.settings_token_status_problem),
        )

        var token by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        val running = state.tokenAction is ActionState.Running

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (state.tokenSet) {
                        stringResource(R.string.settings_token_field_label_replace)
                    } else {
                        stringResource(R.string.settings_token_field_label)
                    },
                )
            },
            singleLine = true,
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(
                        if (visible) {
                            stringResource(R.string.settings_hide)
                        } else {
                            stringResource(R.string.settings_show)
                        },
                    )
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { onSubmitToken(token) },
                enabled = token.isNotBlank() && !running,
            ) {
                Text(
                    if (state.tokenSet) {
                        stringResource(R.string.settings_token_validate_replace)
                    } else {
                        stringResource(R.string.settings_token_validate)
                    },
                )
            }
            if (running) BusyIndicator()
        }

        ActionStatusText(state.tokenAction)
    }
}

// endregion

// region Recipient

@Composable
private fun RecipientCard(
    state: SettingsUiState,
    onCapturePairing: () -> Unit,
    onSubmitManualChatId: (String) -> Unit,
    onSendTest: () -> Unit,
    onClearPairing: () -> Unit,
) {
    SettingsCard {
        // Human-friendly recipient headline; built here (rather than on the state) because it needs
        // resources.
        val chatId = state.chatId
        val displayName = state.chatDisplayName
        val recipientLabel = when {
            chatId == null -> stringResource(R.string.settings_recipient_not_paired)
            !displayName.isNullOrBlank() ->
                stringResource(R.string.settings_recipient_label, displayName, chatId.toString())

            else -> chatId.toString()
        }
        StatusLine(
            ok = state.paired,
            text = recipientLabel,
            okText = stringResource(R.string.settings_recipient_status_ok),
            problemText = stringResource(R.string.settings_recipient_status_problem),
        )

        Text(
            text = stringResource(R.string.settings_recipient_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val pairing = state.pairAction is ActionState.Running
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onCapturePairing,
                enabled = state.tokenSet && !pairing,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_recipient_repair))
            }
            Button(
                onClick = onSendTest,
                enabled = state.paired && state.testAction !is ActionState.Running,
            ) {
                Text(stringResource(R.string.settings_recipient_send_test))
            }
            if (pairing) BusyIndicator()
        }
        ActionStatusText(state.pairAction)
        ActionStatusText(state.testAction)

        HorizontalDivider()

        var manualId by remember { mutableStateOf("") }
        OutlinedTextField(
            value = manualId,
            onValueChange = { input -> manualId = input.filter { it.isDigit() || it == '-' } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_recipient_manual_chat_id)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = {
                    onSubmitManualChatId(manualId)
                    manualId = ""
                },
                enabled = manualId.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_recipient_set_chat_id))
            }
            TextButton(onClick = onClearPairing, enabled = state.paired) {
                Text(stringResource(R.string.settings_recipient_clear_pairing))
            }
        }
    }
}

// endregion

// region Forwarding

@Composable
private fun ForwardingCard(
    state: SettingsUiState,
    onSetPaused: (Boolean) -> Unit,
    onSetIncludeImages: (Boolean) -> Unit,
    onSetIncludeAvatars: (Boolean) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetSkipOngoing: (Boolean) -> Unit,
    onSetExpiryHours: (Int) -> Unit,
) {
    SettingsCard {
        SettingSwitchRow(
            title = stringResource(R.string.settings_pause_title),
            subtitle = stringResource(R.string.settings_pause_subtitle),
            checked = !state.forwardingEnabled,
            onCheckedChange = onSetPaused,
        )
        HorizontalDivider()
        SettingSwitchRow(
            title = stringResource(R.string.settings_include_images_title),
            subtitle = stringResource(R.string.settings_include_images_subtitle),
            checked = state.includeImages,
            onCheckedChange = onSetIncludeImages,
        )
        if (state.includeImages) {
            HorizontalDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_include_avatars_title),
                subtitle = stringResource(R.string.settings_include_avatars_subtitle),
                checked = state.includeAvatars,
                onCheckedChange = onSetIncludeAvatars,
            )
        }
        HorizontalDivider()
        SettingSwitchRow(
            title = stringResource(R.string.settings_wifi_only_title),
            subtitle = stringResource(R.string.settings_wifi_only_subtitle),
            checked = state.wifiOnly,
            onCheckedChange = onSetWifiOnly,
        )
        HorizontalDivider()
        SettingSwitchRow(
            title = stringResource(R.string.settings_skip_ongoing_title),
            subtitle = stringResource(R.string.settings_skip_ongoing_subtitle),
            checked = state.skipOngoing,
            onCheckedChange = onSetSkipOngoing,
        )
        HorizontalDivider()
        ExpiryControl(hours = state.outboxExpiryHours, onChange = onSetExpiryHours)
    }
}

@Composable
private fun ExpiryControl(
    hours: Int,
    onChange: (Int) -> Unit,
) {
    // Local, smooth slider position seeded from the persisted value; committed on release.
    var sliderValue by remember(hours) { mutableFloatStateOf(hours.toFloat()) }
    val current = sliderValue.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_expiry_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.settings_expiry_value, current),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange(current - 1) },
                enabled = current > SettingsUiState.MIN_EXPIRY_HOURS,
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.settings_expiry_decrease),
                    modifier = Modifier.size(18.dp),
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onChange(sliderValue.roundToInt()) },
                valueRange = SettingsUiState.MIN_EXPIRY_HOURS.toFloat()..
                    SettingsUiState.MAX_EXPIRY_HOURS.toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onChange(current + 1) },
                enabled = current < SettingsUiState.MAX_EXPIRY_HOURS,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_expiry_increase),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_expiry_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region Maintenance

@Composable
private fun MaintenanceCard(
    state: SettingsUiState,
    onClearDelivered: () -> Unit,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current

    SettingsCard {
        // Listener health.
        StatusLine(
            ok = state.listenerEnabled,
            text = if (state.listenerEnabled) {
                stringResource(R.string.settings_listener_on)
            } else {
                stringResource(R.string.settings_listener_off)
            },
            okText = stringResource(R.string.settings_listener_status_ok),
            problemText = stringResource(R.string.settings_listener_status_problem),
        )
        OutlinedButton(
            onClick = { context.launchSafely(NotificationAccess.settingsIntent(context)) },
        ) {
            Text(stringResource(R.string.settings_open_notification_access))
        }

        HorizontalDivider()

        Text(
            stringResource(R.string.settings_battery_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_battery_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                context.launchSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
        ) {
            Text(stringResource(R.string.settings_battery_button))
        }

        HorizontalDivider()

        Text(
            stringResource(R.string.settings_delivery_log_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        var confirmClearAll by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClearDelivered) {
                Text(stringResource(R.string.settings_clear_delivered))
            }
            TextButton(onClick = { confirmClearAll = true }) {
                Text(stringResource(R.string.settings_clear_all))
            }
        }
        ActionStatusText(state.maintenanceAction)

        if (confirmClearAll) {
            AlertDialog(
                onDismissRequest = { confirmClearAll = false },
                title = { Text(stringResource(R.string.settings_clear_all_dialog_title)) },
                text = {
                    Text(stringResource(R.string.settings_clear_all_dialog_message))
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearAll = false
                        onClearAll()
                    }) {
                        Text(stringResource(R.string.settings_clear_all))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearAll = false }) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                },
            )
        }
    }
}

// endregion

// region About

@Composable
private fun AboutCard(
    state: SettingsUiState,
    onCheckForUpdates: () -> Unit,
) {
    val context = LocalContext.current

    SettingsCard {
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val checking = state.updateState is UpdateCheckState.Checking
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = onCheckForUpdates, enabled = !checking) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_check_for_updates))
            }
            if (checking) BusyIndicator()
        }

        when (val update = state.updateState) {
            UpdateCheckState.Idle, UpdateCheckState.Checking -> Unit

            is UpdateCheckState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_update_up_to_date, update.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is UpdateCheckState.Available -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.settings_update_available, update.latest),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = {
                        context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                    },
                ) {
                    Text(stringResource(R.string.settings_update_open_release))
                }
            }

            is UpdateCheckState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_update_failed, update.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// endregion

// region Diagnostics (advanced)

/**
 * Remote actions: inline buttons under each forwarded message that act on the device. Off by default
 * because enabling it makes the app poll Telegram for inbound presses.
 */
@Composable
private fun RemoteActionsCard(
    state: SettingsUiState,
    onSetRemoteActionsEnabled: (Boolean) -> Unit,
    onSetRemoteActionsAlwaysOn: (Boolean) -> Unit,
    onSetNowPlayingEnabled: (Boolean) -> Unit,
    onSetNowPlayingSongLink: (Boolean) -> Unit,
) {
    SettingsCard {
        SettingSwitchRow(
            title = stringResource(R.string.settings_action_buttons_title),
            subtitle = stringResource(R.string.settings_action_buttons_subtitle),
            checked = state.remoteActionsEnabled,
            onCheckedChange = onSetRemoteActionsEnabled,
        )
        if (state.remoteActionsEnabled) {
            HorizontalDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_now_playing_title),
                subtitle = stringResource(R.string.settings_now_playing_subtitle),
                checked = state.nowPlayingEnabled,
                onCheckedChange = onSetNowPlayingEnabled,
            )
            // Nested under Now playing: most players have no per-app magic-link toggle to hang this
            // off, so the song link needs its own switch or it can't be turned off at all.
            if (state.nowPlayingEnabled) {
                HorizontalDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_song_link_title),
                    subtitle = stringResource(R.string.settings_song_link_subtitle),
                    checked = state.nowPlayingSongLink,
                    onCheckedChange = onSetNowPlayingSongLink,
                )
            }
            HorizontalDivider()
            SettingSwitchRow(
                title = stringResource(R.string.settings_always_on_title),
                subtitle = stringResource(R.string.settings_always_on_subtitle),
                checked = state.remoteActionsAlwaysOn,
                onCheckedChange = onSetRemoteActionsAlwaysOn,
            )
            Text(
                text = stringResource(R.string.settings_remote_actions_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(
    state: SettingsUiState,
    onSetDiagnosticsEnabled: (Boolean) -> Unit,
    onDumpDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
) {
    SettingsCard {
        // Sensitive-data warning: capture records full notification content.
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_diag_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingSwitchRow(
            title = stringResource(R.string.settings_diag_capture_title),
            subtitle = stringResource(R.string.settings_diag_capture_subtitle),
            checked = state.diagnosticsEnabled,
            onCheckedChange = onSetDiagnosticsEnabled,
        )

        HorizontalDivider()

        val recordsLabel = if (state.diagRecordCount == 1) {
            stringResource(R.string.settings_diag_captured_one)
        } else {
            stringResource(R.string.settings_diag_captured_other, state.diagRecordCount)
        }
        Text(
            text = recordsLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val dumping = state.dumpState is ActionState.Running
        var confirmClear by remember { mutableStateOf(false) }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onDumpDiagnostics,
                enabled = !dumping && state.diagRecordCount > 0,
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_diag_dump))
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = state.diagRecordCount > 0,
            ) {
                Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_diag_clear))
            }
            if (dumping) BusyIndicator()
        }
        ActionStatusText(state.dumpState)

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.settings_diag_clear_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.settings_diag_clear_dialog_message,
                            state.diagRecordCount,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        onClearDiagnostics()
                    }) {
                        Text(stringResource(R.string.settings_diag_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                },
            )
        }
    }
}

// endregion

// region Shared building blocks

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A status headline with an icon reflecting whether the feature is configured/healthy. */
@Composable
private fun StatusLine(
    ok: Boolean,
    text: String,
    okText: String,
    problemText: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (ok) okText else problemText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inline result text for a one-shot [ActionState]; renders nothing while idle. */
@Composable
private fun ActionStatusText(action: ActionState) {
    when (action) {
        ActionState.Idle -> Unit
        ActionState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            BusyIndicator()
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_working),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        is ActionState.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                action.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        is ActionState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                action.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BusyIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
    )
}

/** Start an activity for [intent], swallowing the (rare) missing-activity failure. */
private fun Context.launchSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

/** Walk the [ContextWrapper] chain to find the hosting [Activity], or null if there is none. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// endregion
