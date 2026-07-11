package space.linuxct.teleforward.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.data.secret.SecretStore
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.PairingRepository
import space.linuxct.teleforward.data.telegram.PairingResult
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TokenValidation
import space.linuxct.teleforward.util.NotificationAccess
import javax.inject.Inject

/**
 * State of a one-shot asynchronous action (token validation, pairing, test send, log cleanup).
 * Rendered inline next to the button that triggers it.
 */
sealed interface ActionState {
    data object Idle : ActionState
    data object Running : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

/**
 * Single immutable UI model for the Settings screen. One [StateFlow] of this drives the whole
 * screen (plan: "each Hilt VM exposes one `StateFlow<UiState>`").
 */
data class SettingsUiState(
    val loading: Boolean = true,
    // Bot token
    val tokenSet: Boolean = false,
    val botUsername: String? = null,
    val tokenAction: ActionState = ActionState.Idle,
    // Recipient
    val chatId: Long? = null,
    val chatDisplayName: String? = null,
    val pairAction: ActionState = ActionState.Idle,
    val testAction: ActionState = ActionState.Idle,
    // Toggles
    val forwardingEnabled: Boolean = true,
    val includeImages: Boolean = true,
    val wifiOnly: Boolean = false,
    val skipOngoing: Boolean = true,
    val outboxExpiryHours: Int = DEFAULT_EXPIRY_HOURS,
    // Maintenance
    val listenerEnabled: Boolean = false,
    val maintenanceAction: ActionState = ActionState.Idle,
) {
    val paired: Boolean get() = chatId != null

    /** Human-friendly recipient label for the recipient card. */
    val recipientLabel: String
        get() = when {
            chatId == null -> "Not paired"
            !chatDisplayName.isNullOrBlank() -> "$chatDisplayName · $chatId"
            else -> chatId.toString()
        }

    companion object {
        const val DEFAULT_EXPIRY_HOURS = 48
        const val MIN_EXPIRY_HOURS = 1
        const val MAX_EXPIRY_HOURS = 168 // one week
    }
}

/**
 * Settings screen ViewModel. Reactively mirrors [SettingsRepository] flows and the [SecretStore]
 * token-presence / [NotificationAccess] listener-health signals, and drives the token-validation,
 * pairing, test-send and outbox-maintenance actions against the frozen repositories.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val pairingRepository: PairingRepository,
    private val secretStore: SecretStore,
    private val outboxRepository: OutboxRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // One-shot fill for the initial render + token presence, then flip loading off.
        viewModelScope.launch {
            val tokenSet = secretStore.hasToken()
            _state.update { it.copy(loading = false, tokenSet = tokenSet) }
        }
        // Reactive mirrors: keep the mutable slices live as DataStore / pairing writes land.
        settings.chatId.bind { s, v -> s.copy(chatId = v) }
        settings.chatDisplayName.bind { s, v -> s.copy(chatDisplayName = v) }
        settings.botUsername.bind { s, v -> s.copy(botUsername = v) }
        settings.forwardingEnabled.bind { s, v -> s.copy(forwardingEnabled = v) }
        settings.includeImages.bind { s, v -> s.copy(includeImages = v) }
        settings.wifiOnly.bind { s, v -> s.copy(wifiOnly = v) }
        settings.skipOngoing.bind { s, v -> s.copy(skipOngoing = v) }
        settings.outboxExpiryHours.bind { s, v -> s.copy(outboxExpiryHours = v) }
        refreshListenerHealth()
    }

    private fun <T> Flow<T>.bind(apply: (SettingsUiState, T) -> SettingsUiState) =
        onEach { value -> _state.update { apply(it, value) } }.launchIn(viewModelScope)

    // region Bot token

    /** Validate + persist a (new or replacement) bot token via getMe. */
    fun submitToken(rawToken: String) {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            _state.update { it.copy(tokenAction = ActionState.Error("Enter a bot token")) }
            return
        }
        _state.update { it.copy(tokenAction = ActionState.Running) }
        viewModelScope.launch {
            when (val result = pairingRepository.validateToken(token)) {
                is TokenValidation.Valid -> {
                    val label = result.botUsername?.let { "@$it" } ?: "bot #${result.botId}"
                    _state.update {
                        it.copy(
                            tokenAction = ActionState.Success("Connected to $label"),
                            tokenSet = true,
                            botUsername = result.botUsername ?: it.botUsername,
                        )
                    }
                }

                is TokenValidation.Invalid ->
                    _state.update { it.copy(tokenAction = ActionState.Error(result.reason)) }
            }
        }
    }

    // endregion

    // region Recipient / pairing

    /** Auto-capture the chat that pressed Start (deleteWebhook + getUpdates). */
    fun capturePairing() {
        _state.update { it.copy(pairAction = ActionState.Running) }
        viewModelScope.launch {
            val next = when (val result = pairingRepository.captureChatId()) {
                is PairingResult.Captured ->
                    ActionState.Success("Paired with ${result.displayName ?: result.chatId}")

                PairingResult.NoUpdate ->
                    ActionState.Error("No Start message seen yet — open the bot, press Start, then try again.")

                is PairingResult.Error -> ActionState.Error(result.message)
            }
            _state.update { it.copy(pairAction = next) }
        }
    }

    /** Manual numeric chat_id fallback (e.g. from @userinfobot). */
    fun submitManualChatId(raw: String) {
        val chatId = raw.trim().toLongOrNull()
        if (chatId == null) {
            _state.update { it.copy(pairAction = ActionState.Error("Enter a numeric chat id")) }
            return
        }
        _state.update { it.copy(pairAction = ActionState.Running) }
        viewModelScope.launch {
            pairingRepository.setManualChatId(chatId)
            _state.update { it.copy(pairAction = ActionState.Success("Recipient set to $chatId")) }
        }
    }

    /** Send a test message to the paired chat and report the outcome inline. */
    fun sendTest() {
        _state.update { it.copy(testAction = ActionState.Running) }
        viewModelScope.launch {
            val next = when (val result = pairingRepository.sendTest()) {
                is SendResult.Success -> ActionState.Success("Test message sent")
                is SendResult.RetryAfter -> ActionState.Error("Rate limited — retry in ${result.seconds}s")
                is SendResult.Transient -> ActionState.Error(result.message)
                is SendResult.BadRequest -> ActionState.Error(result.message)
                is SendResult.Terminal -> ActionState.Error(result.message)
            }
            _state.update { it.copy(testAction = next) }
        }
    }

    /** Forget the paired recipient (chat id + display name). */
    fun clearPairing() {
        viewModelScope.launch {
            settings.clearPairing()
            _state.update {
                it.copy(
                    pairAction = ActionState.Idle,
                    testAction = ActionState.Idle,
                )
            }
        }
    }

    // endregion

    // region Toggles

    /** Note: the UI presents this as "Pause forwarding", so [paused] is inverted here. */
    fun setPaused(paused: Boolean) =
        launchSetter { settings.setForwardingEnabled(!paused) }

    fun setIncludeImages(include: Boolean) = launchSetter { settings.setIncludeImages(include) }

    fun setWifiOnly(wifiOnly: Boolean) = launchSetter { settings.setWifiOnly(wifiOnly) }

    fun setSkipOngoing(skip: Boolean) = launchSetter { settings.setSkipOngoing(skip) }

    fun setOutboxExpiryHours(hours: Int) {
        val clamped = hours.coerceIn(
            SettingsUiState.MIN_EXPIRY_HOURS,
            SettingsUiState.MAX_EXPIRY_HOURS,
        )
        launchSetter { settings.setOutboxExpiryHours(clamped) }
    }

    private fun launchSetter(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    // endregion

    // region Maintenance

    /** Delete only SENT rows from the delivery log. */
    fun clearDeliveredLog() {
        viewModelScope.launch {
            outboxRepository.clearSent()
            _state.update { it.copy(maintenanceAction = ActionState.Success("Delivered items cleared")) }
        }
    }

    /** Wipe the entire outbox / delivery log (destructive). */
    fun clearAllLog() {
        viewModelScope.launch {
            outboxRepository.clearAll()
            _state.update { it.copy(maintenanceAction = ActionState.Success("Delivery log cleared")) }
        }
    }

    /** Re-read notification-listener access (call on resume, e.g. returning from Settings). */
    fun refreshListenerHealth() {
        _state.update { it.copy(listenerEnabled = NotificationAccess.isEnabled(appContext)) }
    }

    // endregion
}
