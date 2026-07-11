package space.linuxct.teleforward.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import space.linuxct.teleforward.service.TeleNotificationListener

/**
 * Helpers for the notification-listener special access grant (a manual Settings grant, not a
 * runtime permission dialog). Fully implemented in Wave 0.
 */
object NotificationAccess {

    /** True if this app's notification listener is currently enabled. */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Intent that opens the notification-access settings, deep-linking straight to this app's
     * listener on API 30+ (ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS), else the general list
     * (ACTION_NOTIFICATION_LISTENER_SETTINGS).
     */
    fun settingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(context, TeleNotificationListener::class.java)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component.flattenToString(),
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
