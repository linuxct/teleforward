package space.linuxct.teleforward.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import space.linuxct.teleforward.ui.apps.AppListRoute
import space.linuxct.teleforward.ui.channels.ChannelPickerRoute
import space.linuxct.teleforward.ui.log.DeliveryLogRoute
import space.linuxct.teleforward.ui.onboarding.OnboardingRoute
import space.linuxct.teleforward.ui.settings.SettingsRoute

/**
 * Route constants. `channels/{packageName}` takes the target package as a path argument.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val APPS = "apps"
    const val CHANNELS = "channels/{packageName}"
    const val SETTINGS = "settings"
    const val LOG = "log"

    const val ARG_PACKAGE_NAME = "packageName"

    fun channels(packageName: String): String = "channels/$packageName"
}

/**
 * The app's navigation graph, wiring each route to its (Wave 0 stub) screen composable with the
 * frozen route signatures. [startDestination] is chosen by [space.linuxct.teleforward.MainActivity].
 */
@Composable
fun AppNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onFinished = {
                    navController.navigate(Routes.APPS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.APPS) {
            AppListRoute(
                onOpenChannels = { packageName ->
                    navController.navigate(Routes.channels(packageName))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLog = { navController.navigate(Routes.LOG) },
            )
        }

        composable(
            route = Routes.CHANNELS,
            arguments = listOf(
                navArgument(Routes.ARG_PACKAGE_NAME) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val packageName =
                backStackEntry.arguments?.getString(Routes.ARG_PACKAGE_NAME).orEmpty()
            ChannelPickerRoute(
                packageName = packageName,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }

        composable(Routes.LOG) {
            DeliveryLogRoute(onBack = { navController.popBackStack() })
        }
    }
}
