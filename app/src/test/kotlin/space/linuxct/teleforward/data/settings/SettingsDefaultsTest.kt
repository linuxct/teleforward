package space.linuxct.teleforward.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the opt-out semantics of the remote-action settings.
 *
 * These defaults are only consulted when the key is **absent** from DataStore, and those keys are
 * written solely by the Settings toggles. That is what makes "on unless explicitly turned off"
 * survive updates, so the values are asserted here rather than left to be quietly changed.
 */
class SettingsDefaultsTest {

    @Test
    fun actionButtonsAreOnUntilExplicitlyDisabled() {
        // Existing installs upgrading into the feature have no stored value, so they must read `true`
        // and get buttons. Turning it off writes `false`, which is then honoured forever.
        assertTrue(SettingsKeys.Defaults.REMOTE_ACTIONS_ENABLED)
    }

    @Test
    fun batteryAffectingExtrasStayOptIn() {
        // Both of these keep the inbound poller (or a foreground service) alive well beyond a single
        // notification, so neither may switch itself on for someone who never asked.
        // "Now playing" arms polling repeatedly across a listening session; "Always listening" holds
        // a permanent connection outright.
        assertFalse(SettingsKeys.Defaults.NOW_PLAYING_ENABLED)
        assertFalse(SettingsKeys.Defaults.REMOTE_ACTIONS_ALWAYS_ON)
    }
}
