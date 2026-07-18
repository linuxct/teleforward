package space.linuxct.teleforward.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * Preferences DataStore key definitions and default values, shared by the implementation and any
 * tests. Frozen contract — Wave 1 fills in the impl bodies only.
 */
object SettingsKeys {
    const val DATASTORE_NAME = "teleforward_settings"

    val CHAT_ID = longPreferencesKey("chat_id")
    val CHAT_DISPLAY_NAME = stringPreferencesKey("chat_display_name")
    val BOT_USERNAME = stringPreferencesKey("bot_username")
    val FORWARDING_ENABLED = booleanPreferencesKey("forwarding_enabled")
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    val INCLUDE_IMAGES = booleanPreferencesKey("include_images")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val OUTBOX_EXPIRY_HOURS = intPreferencesKey("outbox_expiry_hours")
    val SKIP_ONGOING = booleanPreferencesKey("skip_ongoing")

    // Contact photos / app logos (the notification's large icon). Separate from INCLUDE_IMAGES
    // because Telegram lays every photo out at full bubble width — a 128px avatar is upscaled to
    // dominate the message. Off by default; real content images (BigPictureStyle) are unaffected.
    val INCLUDE_AVATARS = booleanPreferencesKey("include_avatars")
    val GET_UPDATES_OFFSET = longPreferencesKey("get_updates_offset")
    val LAST_NOTIFIED_UPDATE_VERSION = stringPreferencesKey("last_notified_update_version")

    // Diagnostics (advanced, dev-only forensic logging). Off by default; when on, captures ALL apps
    // unless DIAGNOSTICS_PACKAGES restricts to a CSV allow-list (null/absent = all apps).
    val DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
    val DIAGNOSTICS_PACKAGES = stringPreferencesKey("diagnostics_packages")

    // Magic-link reconstruction. Stores the DISABLED (opt-out) set, so absence ⇒ every supported
    // YouTube app is ON by default (fresh install and after updates, since DataStore persists).
    val MAGIC_LINK_DISABLED_PACKAGES = stringSetPreferencesKey("magic_link_disabled_packages")

    // Remote actions: inline buttons under forwarded messages that dismiss / mark-read / reply on the
    // device. Off by default (it makes the app poll Telegram for inbound presses). ALWAYS_ON upgrades
    // burst polling to a persistent foreground service. Its own getUpdates offset, kept separate from
    // GET_UPDATES_OFFSET so the pairing capture and the poller can never consume each other's updates.
    val REMOTE_ACTIONS_ENABLED = booleanPreferencesKey("remote_actions_enabled")
    val REMOTE_ACTIONS_ALWAYS_ON = booleanPreferencesKey("remote_actions_always_on")
    val REMOTE_ACTIONS_OFFSET = longPreferencesKey("remote_actions_offset")

    // "Now playing" remote control. Media/transport notifications are normally dropped by
    // SKIP_ONGOING; when this is on they instead maintain ONE Telegram message per app, edited in
    // place as the track changes, carrying the app's transport buttons. Off by default.
    val NOW_PLAYING_ENABLED = booleanPreferencesKey("now_playing_enabled")

    object Defaults {
        const val FORWARDING_ENABLED = true
        const val ONBOARDING_COMPLETE = false
        const val INCLUDE_IMAGES = true
        const val INCLUDE_AVATARS = false
        const val WIFI_ONLY = false
        const val OUTBOX_EXPIRY_HOURS = 48
        const val SKIP_ONGOING = true
        const val GET_UPDATES_OFFSET = 0L
        const val DIAGNOSTICS_ENABLED = false
        /**
         * Action buttons are ON unless the user has explicitly turned them off.
         *
         * This default is only consulted when [SettingsKeys.REMOTE_ACTIONS_ENABLED] is **absent** from
         * DataStore, and that key is written in exactly one place — the Settings toggle. So:
         *  - a fresh install, or an existing install upgrading into this feature, has no stored value
         *    and gets buttons enabled;
         *  - switching it off writes `false`, after which the default is never consulted again, so it
         *    stays off through every future update.
         *
         * Do NOT "fix" this by writing the default at startup: that would erase the distinction
         * between "never chose" and "chose off", and would re-enable it for people who opted out.
         */
        const val REMOTE_ACTIONS_ENABLED = true
        const val REMOTE_ACTIONS_ALWAYS_ON = false
        const val REMOTE_ACTIONS_OFFSET = 0L
        /**
         * Opt-in. Unlike a chat notification — a discrete event you may act on once — a media
         * notification re-posts for the whole listening session, so the control keeps arming the
         * inbound poller as tracks change. That's a real battery cost to impose on someone who never
         * asked for it, so it stays off until switched on.
         */
        const val NOW_PLAYING_ENABLED = false
    }
}
