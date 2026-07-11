package space.linuxct.teleforward.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * One-shot snapshot of all settings, for callers that need a synchronous read on a background
 * thread (e.g. the notification listener handoff and the delivery worker).
 */
data class SettingsSnapshot(
    val chatId: Long?,
    val chatDisplayName: String?,
    val botUsername: String?,
    val forwardingEnabled: Boolean,
    val onboardingComplete: Boolean,
    val includeImages: Boolean,
    val wifiOnly: Boolean,
    val outboxExpiryHours: Int,
    val skipOngoing: Boolean,
    val getUpdatesOffset: Long,
)

/**
 * Preferences DataStore-backed app settings. Exposes a reactive [Flow] and a setter for every key,
 * plus a [snapshot] for synchronous one-shot reads.
 *
 * Keys (see [SettingsKeys]): `chat_id`, `chat_display_name`, `bot_username`, `forwarding_enabled`,
 * `onboarding_complete`, `include_images`, `wifi_only`, `outbox_expiry_hours`, `skip_ongoing`,
 * `get_updates_offset`.
 *
 * The bot token is NOT here — it lives in [space.linuxct.teleforward.data.secret.SecretStore].
 */
interface SettingsRepository {

    /** Paired recipient chat id (not secret). Null until pairing completes. */
    val chatId: Flow<Long?>

    /** Display name of the paired chat (first name / @username), for UI. */
    val chatDisplayName: Flow<String?>

    /** Resolved bot `@username` from getMe, for UI + t.me links. */
    val botUsername: Flow<String?>

    /** Global pause switch. When false, nothing is forwarded. Default true. */
    val forwardingEnabled: Flow<Boolean>

    /** Whether the onboarding wizard has been completed. Default false. */
    val onboardingComplete: Flow<Boolean>

    /** Whether to extract and forward images. Default true. */
    val includeImages: Flow<Boolean>

    /** Restrict delivery to unmetered (Wi-Fi) networks. Default false. */
    val wifiOnly: Flow<Boolean>

    /** Hours after which an undelivered outbox item is expired. Default 48. */
    val outboxExpiryHours: Flow<Int>

    /** Skip ongoing/foreground-service notifications. Default true. */
    val skipOngoing: Flow<Boolean>

    /** getUpdates offset bookkeeping used during pairing. Default 0. */
    val getUpdatesOffset: Flow<Long>

    suspend fun setChatId(chatId: Long?)

    suspend fun setChatDisplayName(name: String?)

    suspend fun setBotUsername(username: String?)

    suspend fun setForwardingEnabled(enabled: Boolean)

    suspend fun setOnboardingComplete(complete: Boolean)

    suspend fun setIncludeImages(include: Boolean)

    suspend fun setWifiOnly(wifiOnly: Boolean)

    suspend fun setOutboxExpiryHours(hours: Int)

    suspend fun setSkipOngoing(skip: Boolean)

    suspend fun setGetUpdatesOffset(offset: Long)

    /** Synchronous-friendly one-shot read of every setting. */
    suspend fun snapshot(): SettingsSnapshot

    /** Clear pairing-related settings (chat id + display name); used when re-pairing. */
    suspend fun clearPairing()
}
