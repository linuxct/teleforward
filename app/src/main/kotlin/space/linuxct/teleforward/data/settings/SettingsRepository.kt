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
    val diagnosticsEnabled: Boolean,
    val diagnosticsPackages: String?,
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

    /**
     * Whether to also forward the notification's **large icon** — the contact photo / app logo.
     *
     * Default false, and deliberately separate from [includeImages]: Telegram renders every photo at
     * full bubble width, so a small avatar is upscaled into a blurry block that dwarfs the message it
     * belongs to, and the Bot API offers no way to send an image smaller or inline. Content images
     * (BigPictureStyle — the photo someone actually sent) are governed by [includeImages] and are
     * unaffected by this.
     */
    val includeAvatars: Flow<Boolean>

    /** Restrict delivery to unmetered (Wi-Fi) networks. Default false. */
    val wifiOnly: Flow<Boolean>

    /** Hours after which an undelivered outbox item is expired. Default 48. */
    val outboxExpiryHours: Flow<Int>

    /** Skip ongoing/foreground-service notifications. Default true. */
    val skipOngoing: Flow<Boolean>

    /** getUpdates offset bookkeeping used during pairing. Default 0. */
    val getUpdatesOffset: Flow<Long>

    /**
     * Latest release version the update worker has already notified the user about. Null until the
     * first update notification; used to avoid re-notifying for the same version every day.
     */
    val lastNotifiedUpdateVersion: Flow<String?>

    /**
     * Whether the advanced forensic diagnostics logger is enabled. Off by default. Dev-only feature:
     * when on, the listener captures/probes ALL apps' notifications independent of the forward filter.
     */
    val diagnosticsEnabled: Flow<Boolean>

    /**
     * Optional CSV allow-list of package names the diagnostics logger restricts to. Null (the
     * default) means "all apps". Currently informational for the listener; forward-compatible with a
     * future per-package filter.
     */
    val diagnosticsPackages: Flow<String?>

    /**
     * Packages for which the "magic link" reconstruction is opted OUT. Stored as a disabled set so the
     * default (empty) means every supported app is enabled. "Enabled for pkg" therefore means
     * `magicLinkKind(pkg) != null && pkg ∉ magicLinkDisabledPackages`.
     */
    val magicLinkDisabledPackages: Flow<Set<String>>

    /**
     * Inline action buttons on forwarded messages (dismiss / mark read / reply on the device).
     *
     * **Default true**, and deliberately stored as "explicit user choice only": absence means the user
     * has never touched the toggle, so upgrading installs get buttons without being asked. Turning it
     * off persists `false` and is honoured forever after — the default is never re-applied.
     * Note this implies polling: the app listens for presses in a short burst after each forward.
     */
    val remoteActionsEnabled: Flow<Boolean>

    /**
     * Keep a persistent foreground poller so remote actions fire immediately instead of only in the
     * burst window after a forward. Default false. Only meaningful with [remoteActionsEnabled].
     */
    val remoteActionsAlwaysOn: Flow<Boolean>

    /** `getUpdates` offset for the remote-action poller, kept separate from the pairing offset. */
    val remoteActionsOffset: Flow<Long>

    /**
     * Keep one "now playing" message per media app in the chat — edited in place as the track changes
     * and carrying that app's transport buttons — instead of dropping media notifications entirely.
     * Default false.
     */
    val nowPlayingEnabled: Flow<Boolean>

    /**
     * Whether the now-playing card carries a `🔗` universal song link (resolved from the track + artist
     * via the iTunes Search API and wrapped in a song.link page). Global, because most players have no
     * `MagicLinkKind` and so no per-app magic-link toggle of their own. Default on.
     */
    val nowPlayingSongLink: Flow<Boolean>

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

    suspend fun setLastNotifiedUpdateVersion(version: String?)

    suspend fun setDiagnosticsEnabled(enabled: Boolean)

    suspend fun setDiagnosticsPackages(packages: String?)

    /**
     * Enable/disable magic-link reconstruction for [packageName]. `enabled = true` removes it from the
     * disabled set (back to the default-on state); `enabled = false` adds it.
     */
    suspend fun setMagicLinkEnabled(packageName: String, enabled: Boolean)

    suspend fun setRemoteActionsEnabled(enabled: Boolean)

    suspend fun setRemoteActionsAlwaysOn(enabled: Boolean)

    suspend fun setRemoteActionsOffset(offset: Long)

    suspend fun setNowPlayingEnabled(enabled: Boolean)

    suspend fun setNowPlayingSongLink(enabled: Boolean)

    suspend fun setIncludeAvatars(enabled: Boolean)

    /** Synchronous-friendly one-shot read of every setting. */
    suspend fun snapshot(): SettingsSnapshot

    /** Clear pairing-related settings (chat id + display name); used when re-pairing. */
    suspend fun clearPairing()
}
