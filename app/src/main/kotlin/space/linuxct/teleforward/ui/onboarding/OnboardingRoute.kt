package space.linuxct.teleforward.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.linuxct.teleforward.R
import space.linuxct.teleforward.designsystem.AppScaffold

/**
 * Onboarding wizard: welcome → notification access → bot token → pair recipient → POST_NOTIFICATIONS
 * → finish. Frozen entry signature called by `AppNavHost`; [onFinished] navigates onward once the
 * [OnboardingViewModel] marks onboarding complete.
 */
@Composable
fun OnboardingRoute(onFinished: () -> Unit) {
    val viewModel: OnboardingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The bot-token field is on this screen, so mark the window secure to block screenshots and
    // screen recording while onboarding is visible; clear the flag on leave (plan: Security).
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Poll the special notification-access (and POST_NOTIFICATIONS) grant whenever we resume — the
    // user grants notification access in system Settings and returns to the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSystemState()
    }

    // Navigate away once onboarding is persisted as complete.
    LaunchedEffect(state.completed) {
        if (state.completed) onFinished()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPostNotificationsResult(granted)
    }

    AppScaffold(title = stringResource(R.string.onboarding_title)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StepIndicator(
                current = state.stepNumber - 1,
                total = state.totalSteps,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.onboarding_step_counter,
                        state.stepNumber,
                        state.totalSteps,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                when (state.step) {
                    OnboardingStep.Welcome -> WelcomeStep()
                    OnboardingStep.NotificationAccess -> NotificationAccessStep(
                        granted = state.notificationAccessGranted,
                        onOpenSettings = {
                            runCatching {
                                context.startActivity(viewModel.notificationSettingsIntent())
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.onboarding_open_settings_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )

                    OnboardingStep.BotToken -> BotTokenStep(
                        state = state,
                        onTokenChange = viewModel::onTokenChange,
                        onValidate = viewModel::validateToken,
                    )

                    OnboardingStep.PairRecipient -> PairRecipientStep(
                        state = state,
                        onCapture = viewModel::captureChatId,
                        onManualChange = viewModel::onManualChatIdChange,
                        onApplyManual = viewModel::applyManualChatId,
                        onSendTest = viewModel::sendTest,
                    )

                    OnboardingStep.Features -> FeaturesStep(
                        remoteActionsEnabled = state.remoteActionsEnabled,
                        onSetRemoteActionsEnabled = viewModel::setRemoteActionsEnabled,
                    )

                    OnboardingStep.NotificationsPermission -> NotificationsPermissionStep(
                        granted = state.postNotificationsGranted,
                        onRequest = {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                }
            }

            BottomBar(
                showBack = state.stepNumber > 1,
                isLastStep = state.isLastStep,
                canAdvance = state.canAdvance,
                finishing = state.finishing,
                onBack = viewModel::back,
                onNext = viewModel::next,
                onFinish = viewModel::finish,
            )
        }
    }
}

/** Unwrap a [ContextWrapper] chain to the hosting [Activity], or null if there is none. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---------------------------------------------------------------------------
// Step content
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Text(
        text = stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_welcome_privacy_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NotificationAccessStep(
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_access_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.onboarding_access_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_access_open_settings_button))
    }
    if (granted) {
        StatusMessage(stringResource(R.string.onboarding_access_granted))
    } else {
        StatusMessage(
            stringResource(R.string.onboarding_access_not_granted),
            error = false,
        )
    }
}

@Composable
private fun BotTokenStep(
    state: OnboardingUiState,
    onTokenChange: (String) -> Unit,
    onValidate: () -> Unit,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.onboarding_token_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.onboarding_token_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = state.tokenInput,
        onValueChange = onTokenChange,
        label = { Text(stringResource(R.string.onboarding_token_label)) },
        singleLine = true,
        isError = state.tokenError != null,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(
                    stringResource(
                        if (revealed) {
                            R.string.onboarding_token_hide
                        } else {
                            R.string.onboarding_token_show
                        },
                    ),
                )
            }
        },
        supportingText = state.tokenError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onValidate,
        enabled = !state.validatingToken,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.validatingToken) {
            InlineSpinner()
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_token_validating))
        } else {
            Text(stringResource(R.string.onboarding_token_validate_button))
        }
    }
    if (state.tokenValidated) {
        val handle = state.botUsername?.let { "@$it" }
            ?: stringResource(R.string.onboarding_token_bot_fallback)
        StatusMessage(stringResource(R.string.onboarding_token_connected, handle))
    }
}

@Composable
private fun PairRecipientStep(
    state: OnboardingUiState,
    onCapture: () -> Unit,
    onManualChange: (String) -> Unit,
    onApplyManual: () -> Unit,
    onSendTest: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_pair_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    val link = state.botUsername?.let { "t.me/$it" }
    Text(
        text = if (link != null) {
            stringResource(R.string.onboarding_pair_body_with_link, link)
        } else {
            stringResource(R.string.onboarding_pair_body)
        },
        style = MaterialTheme.typography.bodyLarge,
    )

    Button(
        onClick = onCapture,
        enabled = !state.capturing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.capturing) {
            InlineSpinner()
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_pair_capturing))
        } else {
            Text(stringResource(R.string.onboarding_pair_capture_button))
        }
    }

    Text(
        text = stringResource(R.string.onboarding_pair_manual_title),
        style = MaterialTheme.typography.titleMedium,
    )
    OutlinedTextField(
        value = state.manualChatIdInput,
        onValueChange = onManualChange,
        label = { Text(stringResource(R.string.onboarding_pair_manual_label)) },
        singleLine = true,
        isError = state.manualChatIdError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = state.manualChatIdError?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onApplyManual, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_pair_manual_apply_button))
    }

    state.pairingError?.let { StatusMessage(it, error = true) }
    state.pairingInfo?.let { StatusMessage(it) }

    if (state.chatId != null) {
        val who = state.chatDisplayName
            ?: stringResource(R.string.onboarding_pair_chat_fallback, state.chatId)
        StatusMessage(stringResource(R.string.onboarding_pair_recipient, who))
        Button(
            onClick = onSendTest,
            enabled = !state.sendingTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sendingTest) {
                InlineSpinner()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_pair_sending))
            } else {
                Text(stringResource(R.string.onboarding_pair_send_test_button))
            }
        }
        state.testError?.let { StatusMessage(it, error = true) }
        state.testSuccess?.let { StatusMessage(it) }
    }
}

/**
 * Introduces what a forwarded message will actually do. Both features described here are ON by
 * default, so the wizard names them rather than letting links and buttons show up unexplained — and
 * lets action buttons be switched off right here.
 */
@Composable
private fun FeaturesStep(
    remoteActionsEnabled: Boolean,
    onSetRemoteActionsEnabled: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_features_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.onboarding_features_body),
        style = MaterialTheme.typography.bodyLarge,
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_features_magic_links_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_features_magic_links_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_features_actions_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_features_actions_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = remoteActionsEnabled, onCheckedChange = onSetRemoteActionsEnabled)
            }
            Text(
                text = stringResource(R.string.onboarding_features_actions_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text(
        text = stringResource(R.string.onboarding_features_settings_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotificationsPermissionStep(
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Text(
        text = stringResource(R.string.onboarding_post_notifications_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = stringResource(R.string.onboarding_post_notifications_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (granted) {
        StatusMessage(stringResource(R.string.onboarding_post_notifications_granted))
    } else {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_post_notifications_button))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun StepIndicator(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.surfaceVariant
        repeat(total) { index ->
            Surface(
                color = if (index <= current) active else inactive,
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp),
                content = {},
            )
        }
    }
}

@Composable
private fun StatusMessage(text: String, error: Boolean = false) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = onContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun InlineSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
private fun BottomBar(
    showBack: Boolean,
    isLastStep: Boolean,
    canAdvance: Boolean,
    finishing: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = if (isLastStep) onFinish else onNext,
            enabled = canAdvance && !finishing,
        ) {
            if (finishing) {
                InlineSpinner()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_finishing))
            } else {
                Text(
                    stringResource(
                        if (isLastStep) R.string.onboarding_finish else R.string.onboarding_next,
                    ),
                )
            }
        }
    }
}
