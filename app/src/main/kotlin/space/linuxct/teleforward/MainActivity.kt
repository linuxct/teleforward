package space.linuxct.teleforward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.designsystem.TeleForwardTheme
import space.linuxct.teleforward.ui.navigation.AppNavHost
import space.linuxct.teleforward.ui.navigation.Routes
import space.linuxct.teleforward.util.NotificationAccess
import javax.inject.Inject

/**
 * Single-activity host. Renders the Compose UI under [TeleForwardTheme] and picks the start
 * destination from onboarding-completion + notification-access state.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeleForwardTheme {
                val context = LocalContext.current
                // Gate the first render until onboarding state is known (null = loading).
                val onboardingComplete by settingsRepository.onboardingComplete
                    .collectAsStateWithLifecycle(initialValue = null)

                onboardingComplete?.let { complete ->
                    val start = if (complete && NotificationAccess.isEnabled(context)) {
                        Routes.APPS
                    } else {
                        Routes.ONBOARDING
                    }
                    AppNavHost(startDestination = start)
                }
            }
        }
    }
}
