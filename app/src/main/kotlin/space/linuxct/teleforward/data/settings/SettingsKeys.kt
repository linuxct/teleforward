package space.linuxct.teleforward.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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
    val GET_UPDATES_OFFSET = longPreferencesKey("get_updates_offset")

    object Defaults {
        const val FORWARDING_ENABLED = true
        const val ONBOARDING_COMPLETE = false
        const val INCLUDE_IMAGES = true
        const val WIFI_ONLY = false
        const val OUTBOX_EXPIRY_HOURS = 48
        const val SKIP_ONGOING = true
        const val GET_UPDATES_OFFSET = 0L
    }
}
