package space.linuxct.teleforward.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences DataStore-backed implementation of [SettingsRepository].
 *
 * Each Flow reads the corresponding [SettingsKeys] entry from [dataStore], falling back to the
 * documented default (or `null` for the nullable pairing keys) when absent. Setters write via
 * `dataStore.edit { ... }`; setting a nullable value to `null` removes the key so its Flow emits
 * `null` again.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val chatId: Flow<Long?> =
        dataStore.data.map { it[SettingsKeys.CHAT_ID] }

    override val chatDisplayName: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.CHAT_DISPLAY_NAME] }

    override val botUsername: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.BOT_USERNAME] }

    override val forwardingEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.FORWARDING_ENABLED] ?: SettingsKeys.Defaults.FORWARDING_ENABLED }

    override val onboardingComplete: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.ONBOARDING_COMPLETE] ?: SettingsKeys.Defaults.ONBOARDING_COMPLETE }

    override val includeImages: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.INCLUDE_IMAGES] ?: SettingsKeys.Defaults.INCLUDE_IMAGES }

    override val includeAvatars: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.INCLUDE_AVATARS] ?: SettingsKeys.Defaults.INCLUDE_AVATARS }

    override val wifiOnly: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.WIFI_ONLY] ?: SettingsKeys.Defaults.WIFI_ONLY }

    override val outboxExpiryHours: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.OUTBOX_EXPIRY_HOURS] ?: SettingsKeys.Defaults.OUTBOX_EXPIRY_HOURS }

    override val skipOngoing: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.SKIP_ONGOING] ?: SettingsKeys.Defaults.SKIP_ONGOING }

    override val getUpdatesOffset: Flow<Long> =
        dataStore.data.map { it[SettingsKeys.GET_UPDATES_OFFSET] ?: SettingsKeys.Defaults.GET_UPDATES_OFFSET }

    override val lastNotifiedUpdateVersion: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.LAST_NOTIFIED_UPDATE_VERSION] }

    override val diagnosticsEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.DIAGNOSTICS_ENABLED] ?: SettingsKeys.Defaults.DIAGNOSTICS_ENABLED }

    override val diagnosticsPackages: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.DIAGNOSTICS_PACKAGES] }

    override val magicLinkDisabledPackages: Flow<Set<String>> =
        dataStore.data.map { it[SettingsKeys.MAGIC_LINK_DISABLED_PACKAGES] ?: emptySet() }

    override val remoteActionsEnabled: Flow<Boolean> = dataStore.data.map {
        it[SettingsKeys.REMOTE_ACTIONS_ENABLED] ?: SettingsKeys.Defaults.REMOTE_ACTIONS_ENABLED
    }

    override val remoteActionsAlwaysOn: Flow<Boolean> = dataStore.data.map {
        it[SettingsKeys.REMOTE_ACTIONS_ALWAYS_ON] ?: SettingsKeys.Defaults.REMOTE_ACTIONS_ALWAYS_ON
    }

    override val remoteActionsOffset: Flow<Long> = dataStore.data.map {
        it[SettingsKeys.REMOTE_ACTIONS_OFFSET] ?: SettingsKeys.Defaults.REMOTE_ACTIONS_OFFSET
    }

    override val nowPlayingEnabled: Flow<Boolean> = dataStore.data.map {
        it[SettingsKeys.NOW_PLAYING_ENABLED] ?: SettingsKeys.Defaults.NOW_PLAYING_ENABLED
    }

    override val nowPlayingSongLink: Flow<Boolean> = dataStore.data.map {
        it[SettingsKeys.NOW_PLAYING_SONG_LINK] ?: SettingsKeys.Defaults.NOW_PLAYING_SONG_LINK
    }

    override suspend fun setChatId(chatId: Long?) {
        dataStore.edit { prefs ->
            if (chatId == null) prefs.remove(SettingsKeys.CHAT_ID) else prefs[SettingsKeys.CHAT_ID] = chatId
        }
    }

    override suspend fun setChatDisplayName(name: String?) {
        dataStore.edit { prefs ->
            if (name == null) prefs.remove(SettingsKeys.CHAT_DISPLAY_NAME) else prefs[SettingsKeys.CHAT_DISPLAY_NAME] = name
        }
    }

    override suspend fun setBotUsername(username: String?) {
        dataStore.edit { prefs ->
            if (username == null) prefs.remove(SettingsKeys.BOT_USERNAME) else prefs[SettingsKeys.BOT_USERNAME] = username
        }
    }

    override suspend fun setForwardingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.FORWARDING_ENABLED] = enabled }
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.ONBOARDING_COMPLETE] = complete }
    }

    override suspend fun setIncludeImages(include: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.INCLUDE_IMAGES] = include }
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.WIFI_ONLY] = wifiOnly }
    }

    override suspend fun setOutboxExpiryHours(hours: Int) {
        dataStore.edit { prefs -> prefs[SettingsKeys.OUTBOX_EXPIRY_HOURS] = hours }
    }

    override suspend fun setSkipOngoing(skip: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.SKIP_ONGOING] = skip }
    }

    override suspend fun setGetUpdatesOffset(offset: Long) {
        dataStore.edit { prefs -> prefs[SettingsKeys.GET_UPDATES_OFFSET] = offset }
    }

    override suspend fun setLastNotifiedUpdateVersion(version: String?) {
        dataStore.edit { prefs ->
            if (version == null) {
                prefs.remove(SettingsKeys.LAST_NOTIFIED_UPDATE_VERSION)
            } else {
                prefs[SettingsKeys.LAST_NOTIFIED_UPDATE_VERSION] = version
            }
        }
    }

    override suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.DIAGNOSTICS_ENABLED] = enabled }
    }

    override suspend fun setDiagnosticsPackages(packages: String?) {
        dataStore.edit { prefs ->
            if (packages == null) {
                prefs.remove(SettingsKeys.DIAGNOSTICS_PACKAGES)
            } else {
                prefs[SettingsKeys.DIAGNOSTICS_PACKAGES] = packages
            }
        }
    }

    override suspend fun setMagicLinkEnabled(packageName: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[SettingsKeys.MAGIC_LINK_DISABLED_PACKAGES] ?: emptySet()
            val updated = if (enabled) current - packageName else current + packageName
            if (updated.isEmpty()) {
                prefs.remove(SettingsKeys.MAGIC_LINK_DISABLED_PACKAGES)
            } else {
                prefs[SettingsKeys.MAGIC_LINK_DISABLED_PACKAGES] = updated
            }
        }
    }

    override suspend fun setRemoteActionsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.REMOTE_ACTIONS_ENABLED] = enabled }
    }

    override suspend fun setRemoteActionsAlwaysOn(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.REMOTE_ACTIONS_ALWAYS_ON] = enabled }
    }

    override suspend fun setRemoteActionsOffset(offset: Long) {
        dataStore.edit { prefs -> prefs[SettingsKeys.REMOTE_ACTIONS_OFFSET] = offset }
    }

    override suspend fun setNowPlayingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.NOW_PLAYING_ENABLED] = enabled }
    }

    override suspend fun setNowPlayingSongLink(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.NOW_PLAYING_SONG_LINK] = enabled }
    }

    override suspend fun setIncludeAvatars(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SettingsKeys.INCLUDE_AVATARS] = enabled }
    }

    override suspend fun snapshot(): SettingsSnapshot {
        val prefs = dataStore.data.first()
        return SettingsSnapshot(
            chatId = prefs[SettingsKeys.CHAT_ID],
            chatDisplayName = prefs[SettingsKeys.CHAT_DISPLAY_NAME],
            botUsername = prefs[SettingsKeys.BOT_USERNAME],
            forwardingEnabled = prefs[SettingsKeys.FORWARDING_ENABLED] ?: SettingsKeys.Defaults.FORWARDING_ENABLED,
            onboardingComplete = prefs[SettingsKeys.ONBOARDING_COMPLETE] ?: SettingsKeys.Defaults.ONBOARDING_COMPLETE,
            includeImages = prefs[SettingsKeys.INCLUDE_IMAGES] ?: SettingsKeys.Defaults.INCLUDE_IMAGES,
            wifiOnly = prefs[SettingsKeys.WIFI_ONLY] ?: SettingsKeys.Defaults.WIFI_ONLY,
            outboxExpiryHours = prefs[SettingsKeys.OUTBOX_EXPIRY_HOURS] ?: SettingsKeys.Defaults.OUTBOX_EXPIRY_HOURS,
            skipOngoing = prefs[SettingsKeys.SKIP_ONGOING] ?: SettingsKeys.Defaults.SKIP_ONGOING,
            getUpdatesOffset = prefs[SettingsKeys.GET_UPDATES_OFFSET] ?: SettingsKeys.Defaults.GET_UPDATES_OFFSET,
            diagnosticsEnabled = prefs[SettingsKeys.DIAGNOSTICS_ENABLED] ?: SettingsKeys.Defaults.DIAGNOSTICS_ENABLED,
            diagnosticsPackages = prefs[SettingsKeys.DIAGNOSTICS_PACKAGES],
        )
    }

    override suspend fun clearPairing() {
        dataStore.edit { prefs ->
            prefs.remove(SettingsKeys.CHAT_ID)
            prefs.remove(SettingsKeys.CHAT_DISPLAY_NAME)
            prefs.remove(SettingsKeys.BOT_USERNAME)
            prefs.remove(SettingsKeys.GET_UPDATES_OFFSET)
        }
    }
}
