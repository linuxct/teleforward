package space.linuxct.teleforward.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import space.linuxct.teleforward.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the "update available" notification. Tapping it opens the GitHub release page via
 * `ACTION_VIEW`. Silently does nothing when the user has notifications disabled (or the runtime
 * `POST_NOTIFICATIONS` grant is missing), so the update check never nags a user who opted out.
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Post (or refresh) the update notification for release [latest], linking to [releaseUrl]. */
    fun notifyUpdate(latest: String, releaseUrl: String) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)

        // Respect the user's choice: if notifications are off, skip without side effects.
        if (!manager.areNotificationsEnabled()) return

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
        val pendingIntent = PendingIntent.getActivity(
            context,
            /* requestCode = */ 0,
            viewIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Update available")
            .setContentText("TeleForward $latest is available — tap to update")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted at runtime (edge case vs. areNotificationsEnabled).
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("App updates")
            .setDescription("Notifies you when a newer TeleForward version is available.")
            .build()
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "updates"

        /** Stable id so a re-check for the same version updates the existing notification. */
        const val NOTIFICATION_ID = 4201
    }
}
