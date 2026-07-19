package space.linuxct.teleforward.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.linuxct.teleforward.R
import space.linuxct.teleforward.data.repo.OutboxRepository
import space.linuxct.teleforward.data.secret.SecretStore
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.PairingRepository
import space.linuxct.teleforward.data.telegram.PairingResult
import space.linuxct.teleforward.data.telegram.SendResult
import space.linuxct.teleforward.data.telegram.TokenValidation
import space.linuxct.teleforward.data.update.UpdateRepository
import space.linuxct.teleforward.data.update.UpdateResult
import space.linuxct.teleforward.diag.DiagExporter
import space.linuxct.teleforward.diag.DiagStore
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
 * State of the diagnostics "Dump logs (share)" action. Shares [ActionState]'s Idle/Running/
 * Success/Error shape (and its inline renderer), so it's an alias rather than a parallel type.
 */
typealias DumpState = ActionState

/**
 * State of the "Check for updates" action in the About card. Distinct from [ActionState] because it
 * carries the resolved version / release URL used to render the result and the "Open release" button.
 */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val current: String) : UpdateCheckState
    data class Available(val latest: String, val url: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
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
    val includeAvatars: Boolean = false,
    val wifiOnly: Boolean = false,
    val skipOngoing: Boolean = true,
    val outboxExpiryHours: Int = DEFAULT_EXPIRY_HOURS,
    // Maintenance
    val listenerEnabled: Boolean = false,
    val maintenanceAction: ActionState = ActionState.Idle,
    // About / update check
    val updateState: UpdateCheckState = UpdateCheckState.Idle,
    // Diagnostics (advanced)
    val diagnosticsEnabled: Boolean = false,
    val remoteActionsEnabled: Boolean = false,
    val remoteActionsAlwaysOn: Boolean = false,
    val nowPlayingEnabled: Boolean = false,
    val nowPlayingSongLink: Boolean = true,
    val diagRecordCount: Int = 0,
    val dumpState: DumpState = ActionState.Idle,
) {
    val paired: Boolean get() = chatId != null

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
    private val updateRepository: UpdateRepository,
    private val diagStore: DiagStore,
    private val diagExporter: DiagExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * One-shot share-sheet intents emitted by [dumpDiagnostics]. The VM never starts activities
     * itself; [SettingsRoute] collects this and fires each intent through its `launchSafely` path.
     */
    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shareIntents: Flow<Intent> = _shareIntents.receiveAsFlow()

    init {
        // One-shot fill for the initial render + token presence + diag status, then flip loading off.
        viewModelScope.launch {
            val tokenSet = secretStore.hasToken()
            _state.update {
                it.copy(
                    loading = false,
                    tokenSet = tokenSet,
                )
            }
            loadDiagCount()
        }
        // Reactive mirrors: keep the mutable slices live as DataStore / pairing writes land.
        settings.chatId.bind { s, v -> s.copy(chatId = v) }
        settings.chatDisplayName.bind { s, v -> s.copy(chatDisplayName = v) }
        settings.botUsername.bind { s, v -> s.copy(botUsername = v) }
        settings.forwardingEnabled.bind { s, v -> s.copy(forwardingEnabled = v) }
        settings.includeImages.bind { s, v -> s.copy(includeImages = v) }
        settings.includeAvatars.bind { s, v -> s.copy(includeAvatars = v) }
        settings.wifiOnly.bind { s, v -> s.copy(wifiOnly = v) }
        settings.skipOngoing.bind { s, v -> s.copy(skipOngoing = v) }
        settings.outboxExpiryHours.bind { s, v -> s.copy(outboxExpiryHours = v) }
        settings.diagnosticsEnabled.bind { s, v -> s.copy(diagnosticsEnabled = v) }
        settings.remoteActionsEnabled.bind { s, v -> s.copy(remoteActionsEnabled = v) }
        settings.remoteActionsAlwaysOn.bind { s, v -> s.copy(remoteActionsAlwaysOn = v) }
        settings.nowPlayingEnabled.bind { s, v -> s.copy(nowPlayingEnabled = v) }
        settings.nowPlayingSongLink.bind { s, v -> s.copy(nowPlayingSongLink = v) }
        refreshListenerHealth()
    }

    private fun <T> Flow<T>.bind(apply: (SettingsUiState, T) -> SettingsUiState) =
        onEach { value -> _state.update { apply(it, value) } }.launchIn(viewModelScope)

    // region Bot token

    /** Validate + persist a (new or replacement) bot token via getMe. */
    fun submitToken(rawToken: String) {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            _state.update {
                it.copy(
                    tokenAction = ActionState.Error(
                        appContext.getString(R.string.settings_error_enter_token),
                    ),
                )
            }
            return
        }
        _state.update { it.copy(tokenAction = ActionState.Running) }
        viewModelScope.launch {
            when (val result = pairingRepository.validateToken(token)) {
                is TokenValidation.Valid -> {
                    val connected = result.botUsername?.let { username ->
                        appContext.getString(R.string.settings_token_connected_username, username)
                    } ?: appContext.getString(R.string.settings_token_connected_id, result.botId)
                    _state.update {
                        it.copy(
                            tokenAction = ActionState.Success(connected),
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
                is PairingResult.Captured -> ActionState.Success(
                    appContext.getString(
                        R.string.settings_pair_success,
                        result.displayName ?: result.chatId.toString(),
                    ),
                )

                PairingResult.NoUpdate -> ActionState.Error(
                    appContext.getString(R.string.settings_pair_no_update),
                )

                is PairingResult.Error -> ActionState.Error(result.message)
            }
            _state.update { it.copy(pairAction = next) }
        }
    }

    /** Manual numeric chat_id fallback (e.g. from @userinfobot). */
    fun submitManualChatId(raw: String) {
        val chatId = raw.trim().toLongOrNull()
        if (chatId == null) {
            _state.update {
                it.copy(
                    pairAction = ActionState.Error(
                        appContext.getString(R.string.settings_error_numeric_chat_id),
                    ),
                )
            }
            return
        }
        _state.update { it.copy(pairAction = ActionState.Running) }
        viewModelScope.launch {
            pairingRepository.setManualChatId(chatId)
            _state.update {
                it.copy(
                    pairAction = ActionState.Success(
                        appContext.getString(R.string.settings_recipient_set, chatId),
                    ),
                )
            }
        }
    }

    /** Send a test message to the paired chat and report the outcome inline. */
    fun sendTest() {
        _state.update { it.copy(testAction = ActionState.Running) }
        viewModelScope.launch {
            val next = when (val result = pairingRepository.sendTest()) {
                is SendResult.Success ->
                    ActionState.Success(appContext.getString(R.string.settings_test_sent))

                is SendResult.RetryAfter -> ActionState.Error(
                    appContext.getString(R.string.settings_error_rate_limited, result.seconds),
                )

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

    fun setIncludeAvatars(include: Boolean) = launchSetter { settings.setIncludeAvatars(include) }

    fun setWifiOnly(wifiOnly: Boolean) = launchSetter { settings.setWifiOnly(wifiOnly) }

    fun setSkipOngoing(skip: Boolean) = launchSetter { settings.setSkipOngoing(skip) }

    /** Turning remote actions off also tears down the always-on listener, which is meaningless alone. */
    fun setRemoteActionsEnabled(enabled: Boolean) = launchSetter {
        settings.setRemoteActionsEnabled(enabled)
        if (!enabled) settings.setRemoteActionsAlwaysOn(false)
    }

    fun setRemoteActionsAlwaysOn(enabled: Boolean) =
        launchSetter { settings.setRemoteActionsAlwaysOn(enabled) }

    fun setNowPlayingEnabled(enabled: Boolean) =
        launchSetter { settings.setNowPlayingEnabled(enabled) }

    fun setNowPlayingSongLink(enabled: Boolean) =
        launchSetter { settings.setNowPlayingSongLink(enabled) }

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
            _state.update {
                it.copy(
                    maintenanceAction = ActionState.Success(
                        appContext.getString(R.string.settings_maintenance_delivered_cleared),
                    ),
                )
            }
        }
    }

    /** Wipe the entire outbox / delivery log (destructive). */
    fun clearAllLog() {
        viewModelScope.launch {
            outboxRepository.clearAll()
            _state.update {
                it.copy(
                    maintenanceAction = ActionState.Success(
                        appContext.getString(R.string.settings_maintenance_log_cleared),
                    ),
                )
            }
        }
    }

    /** Re-read notification-listener access (call on resume, e.g. returning from Settings). */
    fun refreshListenerHealth() {
        _state.update { it.copy(listenerEnabled = NotificationAccess.isEnabled(appContext)) }
    }

    // endregion

    // region About / update check

    /** Run an immediate GitHub update check and reflect the outcome inline in the About card. */
    fun onCheckForUpdates() {
        _state.update { it.copy(updateState = UpdateCheckState.Checking) }
        viewModelScope.launch {
            val next = when (val result = updateRepository.check()) {
                is UpdateResult.UpToDate -> UpdateCheckState.UpToDate(result.current)
                is UpdateResult.Available -> UpdateCheckState.Available(result.latest, result.releaseUrl)
                is UpdateResult.Failed -> UpdateCheckState.Error(result.message)
            }
            _state.update { it.copy(updateState = next) }
        }
    }

    // endregion

    // region Diagnostics (advanced)

    /** Toggle forensic capture on/off; the listener reads this flag reactively. */
    fun setDiagnosticsEnabled(enabled: Boolean) =
        launchSetter { settings.setDiagnosticsEnabled(enabled) }

    /**
     * Build the diagnostics dump off the main thread and, on success, emit the share-sheet [Intent]
     * as a one-shot event for [SettingsRoute] to fire. Reports the outcome inline via [DumpState].
     */
    fun dumpDiagnostics() {
        _state.update { it.copy(dumpState = ActionState.Running) }
        viewModelScope.launch {
            runCatching { diagExporter.exportToShareIntent() }.fold(
                onSuccess = { intent ->
                    _shareIntents.send(intent)
                    _state.update {
                        it.copy(
                            dumpState = ActionState.Success(
                                appContext.getString(R.string.settings_diag_dump_ready),
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            dumpState = ActionState.Error(
                                error.message
                                    ?: appContext.getString(R.string.settings_diag_dump_failed),
                            ),
                        )
                    }
                },
            )
            loadDiagCount()
        }
    }

    /** Delete every captured forensic record, then refresh the displayed count. */
    fun clearDiagnostics() {
        viewModelScope.launch {
            diagStore.clear()
            loadDiagCount()
            _state.update { it.copy(dumpState = ActionState.Idle) }
        }
    }

    /** Read the current record count into the UI state (suspend; used on load and after actions). */
    private suspend fun loadDiagCount() {
        val count = diagStore.count()
        _state.update { it.copy(diagRecordCount = count) }
    }

    // endregion
}
