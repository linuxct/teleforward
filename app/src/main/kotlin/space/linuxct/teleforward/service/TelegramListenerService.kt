package space.linuxct.teleforward.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.teleforward.R
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.TelegramPoller
import javax.inject.Inject

/**
 * The opt-in "always listening" half of the hybrid inbound strategy: a foreground service that
 * long-polls Telegram continuously so a remote action fires immediately, instead of only during the
 * burst window after a forward.
 *
 * A foreground service is the only way to hold a long-lived network loop on modern Android, and it
 * requires a permanent notification — which is exactly the trade-off this is opt-in for. It stops
 * itself if either remote-actions setting is turned off.
 */
@AndroidEntryPoint
class TelegramListenerService : Service() {

    @Inject lateinit var poller: TelegramPoller

    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart the loop if the system revived us after a process kill.
        if (pollJob?.isActive != true) startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                // Honour a toggle flipped while we were running.
                val enabled = settings.remoteActionsEnabled.first() &&
                    settings.remoteActionsAlwaysOn.first()
                if (!enabled) {
                    stopSelf()
                    return@launch
                }
                // A failed cycle (offline, unpaired, or another getUpdates consumer) backs off briefly
                // rather than hot-looping.
                if (!poller.pollOnce()) delay(BACKOFF_MS)
            }
        }
    }

    private fun startForegroundCompat() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.remote_actions_channel_name))
                .setDescription(getString(R.string.remote_actions_channel_description))
                .build(),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(getString(R.string.remote_actions_ongoing_title))
            .setContentText(getString(R.string.remote_actions_ongoing_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "remote_actions"
        private const val NOTIFICATION_ID = 4242

        /**
         * Whether the listener is genuinely running *right now*.
         *
         * The burst poller stands down while this is true, to avoid two `getUpdates` consumers
         * trading 409s. It deliberately tracks reality rather than the "Always listening" setting:
         * the setting can read `true` while nothing is listening at all — after a reboot, or when
         * Android 12+ refuses a background foreground-service start — and standing down on the
         * strength of a lie would leave presses with nobody to answer them.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Pause after a failed poll cycle so a persistent failure can't spin the CPU/radio. */
        private const val BACKOFF_MS = 15_000L

        /**
         * Start listening. Must be called while the app is in the foreground (a settings toggle),
         * since Android 12+ forbids starting a foreground service from the background. Best-effort.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, TelegramListenerService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TelegramListenerService::class.java)) }
        }
    }
}
