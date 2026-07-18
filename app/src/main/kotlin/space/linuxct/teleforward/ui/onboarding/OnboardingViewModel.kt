package space.linuxct.teleforward.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.linuxct.teleforward.R
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.PairingRepository
import space.linuxct.teleforward.data.telegram.PairingResult
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TokenValidation
import space.linuxct.teleforward.util.NotificationAccess
import javax.inject.Inject

/** The ordered steps of the onboarding wizard. */
enum class OnboardingStep {
    Welcome,
    NotificationAccess,
    BotToken,
    PairRecipient,

    /**
     * What forwarded messages will actually do — magic links and action buttons. Both are on by
     * default, so the wizard introduces them rather than letting them appear unannounced.
     */
    Features,
    NotificationsPermission,
}

/**
 * Single immutable UI state for the onboarding wizard, exposed as a [StateFlow] by
 * [OnboardingViewModel]. Text-field contents live here too so the wizard survives configuration
 * changes without extra saveable state.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val stepNumber: Int = 1,
    val totalSteps: Int = 1,
    val isLastStep: Boolean = false,

    // Step 2 — notification access
    val notificationAccessGranted: Boolean = false,

    // Step 3 — bot token
    val tokenInput: String = "",
    val validatingToken: Boolean = false,
    val tokenValidated: Boolean = false,
    val botUsername: String? = null,
    val botId: Long? = null,
    val tokenError: String? = null,

    // Step 4 — pair recipient
    val capturing: Boolean = false,
    val pairingInfo: String? = null,
    val pairingError: String? = null,
    val chatId: Long? = null,
    val chatDisplayName: String? = null,
    val manualChatIdInput: String = "",
    val manualChatIdError: String? = null,
    val sendingTest: Boolean = false,
    val testSuccess: String? = null,
    val testError: String? = null,

    // Step 5 — what's included (mirrors the live setting so it can be opted out of right here)
    val remoteActionsEnabled: Boolean = true,

    // Step 6 — POST_NOTIFICATIONS
    val postNotificationsGranted: Boolean = false,

    // Finishing
    val finishing: Boolean = false,
    val completed: Boolean = false,
) {
    /** Whether the primary button may advance past the current step. */
    val canAdvance: Boolean
        get() = when (step) {
            OnboardingStep.Welcome -> true
            OnboardingStep.NotificationAccess -> notificationAccessGranted
            OnboardingStep.BotToken -> tokenValidated
            OnboardingStep.PairRecipient -> chatId != null
            // Informational; nothing to satisfy.
            OnboardingStep.Features -> true
            // POST_NOTIFICATIONS is skippable — always allowed to finish.
            OnboardingStep.NotificationsPermission -> true
        }
}

/**
 * Drives the onboarding wizard (welcome → notification access → bot token → pair recipient →
 * POST_NOTIFICATIONS → finish). Business/async logic lives here; the composable owns the
 * Android-specific surface (launching the Settings intent, the permission launcher, resume polling).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val pairingRepository: PairingRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    // POST_NOTIFICATIONS only exists (and is only requestable) on API 33+.
    private val requiresPostNotifications: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val steps: List<OnboardingStep> = buildList {
        add(OnboardingStep.Welcome)
        add(OnboardingStep.NotificationAccess)
        add(OnboardingStep.BotToken)
        add(OnboardingStep.PairRecipient)
        add(OnboardingStep.Features)
        if (requiresPostNotifications) add(OnboardingStep.NotificationsPermission)
    }

    private val _state = MutableStateFlow(stateForStep(OnboardingStep.Welcome, OnboardingUiState()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refreshSystemState()
        // Mirror the live setting so the "what's included" step shows (and can change) the real value.
        settings.remoteActionsEnabled
            .onEach { enabled -> _state.update { it.copy(remoteActionsEnabled = enabled) } }
            .launchIn(viewModelScope)
    }

    /** Opt out of action buttons straight from the wizard; persisted like the Settings toggle. */
    fun setRemoteActionsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setRemoteActionsEnabled(enabled) }
    }

    private fun stateForStep(step: OnboardingStep, base: OnboardingUiState): OnboardingUiState {
        val index = steps.indexOf(step).coerceAtLeast(0)
        return base.copy(
            step = step,
            stepNumber = index + 1,
            totalSteps = steps.size,
            isLastStep = index == steps.lastIndex,
        )
    }

    /** Re-read grant state for the special notification-listener access and POST_NOTIFICATIONS. */
    fun refreshSystemState() {
        val listenerGranted = NotificationAccess.isEnabled(appContext)
        val postGranted = if (requiresPostNotifications) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _state.update {
            it.copy(
                notificationAccessGranted = listenerGranted,
                postNotificationsGranted = postGranted,
            )
        }
    }

    /** Intent that deep-links to this app's notification-listener settings entry. */
    fun notificationSettingsIntent(): Intent = NotificationAccess.settingsIntent(appContext)

    fun next() {
        val current = _state.value.step
        val index = steps.indexOf(current)
        if (index in 0 until steps.lastIndex) {
            _state.update { stateForStep(steps[index + 1], it) }
        }
    }

    fun back() {
        val current = _state.value.step
        val index = steps.indexOf(current)
        if (index > 0) {
            _state.update { stateForStep(steps[index - 1], it) }
        }
    }

    // --- Step 3: bot token -------------------------------------------------

    fun onTokenChange(value: String) {
        _state.update {
            it.copy(
                tokenInput = value,
                // A new token invalidates any prior validation.
                tokenValidated = false,
                botUsername = null,
                botId = null,
                tokenError = null,
            )
        }
    }

    fun validateToken() {
        val token = _state.value.tokenInput.trim()
        if (token.isEmpty()) {
            _state.update {
                it.copy(tokenError = appContext.getString(R.string.onboarding_error_token_empty))
            }
            return
        }
        _state.update { it.copy(validatingToken = true, tokenError = null) }
        viewModelScope.launch {
            val result = runCatching { pairingRepository.validateToken(token) }
                .getOrElse {
                    TokenValidation.Invalid(
                        it.message
                            ?: appContext.getString(R.string.onboarding_error_validation_failed),
                    )
                }
            _state.update {
                when (result) {
                    is TokenValidation.Valid -> it.copy(
                        validatingToken = false,
                        tokenValidated = true,
                        botUsername = result.botUsername,
                        botId = result.botId,
                        tokenError = null,
                    )

                    is TokenValidation.Invalid -> it.copy(
                        validatingToken = false,
                        tokenValidated = false,
                        botUsername = null,
                        botId = null,
                        tokenError = result.reason,
                    )
                }
            }
        }
    }

    // --- Step 4: pair recipient --------------------------------------------

    fun onManualChatIdChange(value: String) {
        _state.update { it.copy(manualChatIdInput = value, manualChatIdError = null) }
    }

    fun captureChatId() {
        if (_state.value.capturing) return
        _state.update {
            it.copy(capturing = true, pairingInfo = null, pairingError = null)
        }
        viewModelScope.launch {
            val result = runCatching { pairingRepository.captureChatId() }
                .getOrElse {
                    PairingResult.Error(
                        it.message
                            ?: appContext.getString(R.string.onboarding_error_pairing_failed),
                    )
                }
            _state.update {
                when (result) {
                    is PairingResult.Captured -> it.copy(
                        capturing = false,
                        chatId = result.chatId,
                        chatDisplayName = result.displayName,
                        pairingInfo = appContext.getString(
                            R.string.onboarding_pair_paired,
                            result.displayName ?: appContext.getString(
                                R.string.onboarding_pair_chat_fallback,
                                result.chatId,
                            ),
                        ),
                        pairingError = null,
                    )

                    PairingResult.NoUpdate -> it.copy(
                        capturing = false,
                        pairingInfo = appContext.getString(R.string.onboarding_pair_no_update),
                        pairingError = null,
                    )

                    is PairingResult.Error -> it.copy(
                        capturing = false,
                        pairingError = result.message,
                    )
                }
            }
        }
    }

    fun applyManualChatId() {
        val raw = _state.value.manualChatIdInput.trim()
        val id = raw.toLongOrNull()
        if (id == null) {
            _state.update {
                it.copy(
                    manualChatIdError = appContext.getString(
                        R.string.onboarding_error_manual_chat_id_invalid,
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching { pairingRepository.setManualChatId(id) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            chatId = id,
                            chatDisplayName = appContext.getString(
                                R.string.onboarding_pair_chat_display_name,
                                id,
                            ),
                            manualChatIdError = null,
                            pairingInfo = appContext.getString(
                                R.string.onboarding_pair_recipient_set,
                                id,
                            ),
                            pairingError = null,
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            manualChatIdError = t.message
                                ?: appContext.getString(R.string.onboarding_error_set_chat_id),
                        )
                    }
                }
        }
    }

    fun sendTest() {
        if (_state.value.sendingTest) return
        _state.update { it.copy(sendingTest = true, testSuccess = null, testError = null) }
        viewModelScope.launch {
            val result = runCatching { pairingRepository.sendTest() }
                .getOrElse {
                    SendResult.Transient(
                        it.message ?: appContext.getString(R.string.onboarding_error_send_failed),
                    )
                }
            _state.update {
                when (result) {
                    is SendResult.Success -> it.copy(
                        sendingTest = false,
                        testSuccess = appContext.getString(R.string.onboarding_test_sent),
                        testError = null,
                    )

                    is SendResult.RetryAfter -> it.copy(
                        sendingTest = false,
                        testError = appContext.getString(
                            R.string.onboarding_error_rate_limited,
                            result.seconds,
                        ),
                    )

                    is SendResult.Transient -> it.copy(
                        sendingTest = false,
                        testError = appContext.getString(
                            R.string.onboarding_error_network,
                            result.message,
                        ),
                    )

                    is SendResult.BadRequest -> it.copy(
                        sendingTest = false,
                        testError = result.message,
                    )

                    is SendResult.Terminal -> it.copy(
                        sendingTest = false,
                        testError = result.message,
                    )
                }
            }
        }
    }

    // --- Step 5: POST_NOTIFICATIONS ----------------------------------------

    fun onPostNotificationsResult(granted: Boolean) {
        _state.update { it.copy(postNotificationsGranted = granted) }
    }

    // --- Finish ------------------------------------------------------------

    fun finish() {
        if (_state.value.finishing || _state.value.completed) return
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            runCatching { settings.setOnboardingComplete(true) }
            _state.update { it.copy(finishing = false, completed = true) }
        }
    }
}
