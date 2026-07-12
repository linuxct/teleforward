package space.linuxct.teleforward.ui.channels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.link.magicLinkKind
import space.linuxct.teleforward.data.repo.AppCatalogRepository
import space.linuxct.teleforward.data.repo.RulesRepository
import space.linuxct.teleforward.data.repo.SeenConversationRepository
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.domain.RuleMode
import space.linuxct.teleforward.domain.SeenChannel
import space.linuxct.teleforward.domain.SeenConversation
import space.linuxct.teleforward.domain.SelectionRule
import space.linuxct.teleforward.ui.navigation.Routes
import javax.inject.Inject

/** A single channel row, projecting a [SeenChannel] together with its current rule state. */
data class ChannelRowUi(
    val channelId: String,
    val name: String,
    val importance: Int?,
    val lastSeen: Long,
    /** true when an enabled per-channel INCLUDE rule exists (the channel switch state). */
    val enabled: Boolean,
)

/** A single conversation row, projecting a [SeenConversation] together with its current rule state. */
data class ConversationRowUi(
    val conversationId: String,
    /** The channel this conversation lives on (stored on the rule when toggled). */
    val channelId: String,
    val title: String,
    val lastSeen: Long,
    /** true when an enabled per-conversation INCLUDE rule exists (the conversation switch state). */
    val enabled: Boolean,
)

/** Single immutable UI state for the channel picker screen. */
data class ChannelPickerUiState(
    val loading: Boolean = true,
    val packageName: String = "",
    val appLabel: String = "",
    val wholeAppEnabled: Boolean = false,
    val channels: List<ChannelRowUi> = emptyList(),
    val conversations: List<ConversationRowUi> = emptyList(),
    /** True only for supported apps: gates the magic-link reconstruction card. */
    val magicLinkSupported: Boolean = false,
    /** Magic-link toggle state (default ON for supported apps until explicitly opted out). */
    val magicLinkEnabled: Boolean = true,
)

/**
 * Drives the per-app channel picker for the package passed as the `channels/{packageName}` nav arg.
 * Combines the seen-channels catalog with the app's rules so the whole-app toggle and each channel
 * switch reflect current state.
 */
@HiltViewModel
class ChannelPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appCatalogRepository: AppCatalogRepository,
    private val rulesRepository: RulesRepository,
    private val seenConversationRepository: SeenConversationRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val packageName: String =
        savedStateHandle.get<String>(Routes.ARG_PACKAGE_NAME).orEmpty()

    private val appLabel: Flow<String> = flow {
        emit(packageName)
        emit(runCatching { appCatalogRepository.getAppLabel(packageName) }.getOrDefault(packageName))
    }

    val uiState: StateFlow<ChannelPickerUiState> = combine(
        appCatalogRepository.observeChannelsForPackage(packageName),
        rulesRepository.observeRulesForPackage(packageName),
        seenConversationRepository.observeForPackage(packageName),
        appLabel,
        settingsRepository.magicLinkDisabledPackages,
    ) { channels, rules, conversations, label, magicLinkDisabled ->
        buildState(channels, rules, conversations, label, magicLinkDisabled)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChannelPickerUiState(packageName = packageName, appLabel = packageName),
    )

    /** Whole-app toggle: INCLUDE the app when turned on, remove the whole-app rule when turned off. */
    fun onToggleWholeApp(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepository.setWholeAppRule(packageName, RuleMode.INCLUDE, enabled = true)
            } else {
                rulesRepository.removeRule(packageName, channelId = null)
            }
        }
    }

    /** Per-channel toggle. An explicit channel rule takes precedence over the whole-app rule. */
    fun onToggleChannel(channelId: String, enabled: Boolean) {
        viewModelScope.launch {
            rulesRepository.setChannelRule(packageName, channelId, RuleMode.INCLUDE, enabled)
        }
    }

    /** Per-conversation toggle. A conversation rule takes precedence over channel and whole-app. */
    fun onToggleConversation(conversationId: String, channelId: String, enabled: Boolean) {
        viewModelScope.launch {
            rulesRepository.setConversationRule(
                packageName = packageName,
                channelId = channelId,
                conversationId = conversationId,
                mode = RuleMode.INCLUDE,
                enabled = enabled,
            )
        }
    }

    /** Magic-link toggle: opt this app in/out of link reconstruction (default on for YouTube apps). */
    fun onToggleMagicLink(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setMagicLinkEnabled(packageName, enabled)
        }
    }

    private fun buildState(
        channels: List<SeenChannel>,
        rules: List<SelectionRule>,
        conversations: List<SeenConversation>,
        label: String,
        magicLinkDisabled: Set<String>,
    ): ChannelPickerUiState {
        val wholeAppEnabled = rules.any {
            it.channelId == null && it.conversationId == null &&
                it.enabled && it.mode == RuleMode.INCLUDE
        }
        val channelRows = channels
            .map { channel ->
                ChannelRowUi(
                    channelId = channel.channelId,
                    name = channel.name?.takeIf { it.isNotBlank() } ?: channel.channelId,
                    importance = channel.importance,
                    lastSeen = channel.lastSeen,
                    enabled = rules.any {
                        it.conversationId == null && it.channelId == channel.channelId &&
                            it.enabled && it.mode == RuleMode.INCLUDE
                    },
                )
            }
            .sortedByDescending { it.lastSeen }
            // The same channelId can be seen under multiple user profiles (userSerial); rules are
            // keyed by (package, channelId), so collapse duplicates — this also keeps the row keys
            // unique (a duplicate LazyColumn key crashes the screen).
            .distinctBy { it.channelId }

        val conversationRows = conversations
            .map { conversation ->
                ConversationRowUi(
                    conversationId = conversation.conversationId,
                    channelId = conversation.channelId,
                    title = conversation.title?.takeIf { it.isNotBlank() }
                        ?: conversation.conversationId,
                    lastSeen = conversation.lastSeen,
                    enabled = rules.any {
                        it.conversationId == conversation.conversationId &&
                            it.enabled && it.mode == RuleMode.INCLUDE
                    },
                )
            }
            .sortedByDescending { it.lastSeen }
            .distinctBy { it.conversationId }

        return ChannelPickerUiState(
            loading = false,
            packageName = packageName,
            appLabel = label,
            wholeAppEnabled = wholeAppEnabled,
            channels = channelRows,
            conversations = conversationRows,
            magicLinkSupported = magicLinkKind(packageName) != null,
            magicLinkEnabled = packageName !in magicLinkDisabled,
        )
    }
}
