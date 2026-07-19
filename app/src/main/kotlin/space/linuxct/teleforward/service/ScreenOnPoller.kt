package space.linuxct.teleforward.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.TelegramPoller
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listens for button presses **for as long as the screen is on**.
 *
 * ## Why the screen
 *
 * A press can only be answered if something is listening at that exact moment — Telegram stops
 * accepting an answer seconds afterwards, so a poll that arrives later can perform the action but can
 * never clear the spinner. Bursts and catch-up polls therefore cannot deliver an immediate response,
 * however cleverly they are scheduled. Only a connection that is already open can.
 *
 * Holding one open permanently is what "Always listening" does, and it costs a permanent notification.
 * But the screen being on is an excellent proxy for "the user might press a button in the next few
 * seconds": if they are reading the chat on this phone, the screen is lit. And the incremental cost is
 * close to nothing, because a lit screen already dwarfs a held socket.
 *
 * ## Why it needs no service of its own
 *
 * This runs inside [TeleNotificationListener], which the system keeps bound for as long as
 * notification access is granted — the app already has a process alive at all times, it simply wasn't
 * using it for this. So there is no second foreground service and no extra notification. Doze doesn't
 * interfere either: it doesn't apply while the screen is on.
 *
 * Stands down when the always-on service is running, so the two can never fight over `getUpdates`.
 */
@Singleton
class ScreenOnPoller @Inject constructor(
    private val poller: TelegramPoller,
    private val settings: SettingsRepository,
) {

    private var receiver: BroadcastReceiver? = null
    private var loop: Job? = null

    /** Begin following the screen. Safe to call repeatedly; the listener service can rebind. */
    fun attach(context: Context, scope: CoroutineScope) {
        if (receiver != null) return
        val appContext = context.applicationContext
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> start(scope)
                    Intent.ACTION_SCREEN_OFF -> stop()
                }
            }
        }
        // SCREEN_ON/OFF cannot be declared in the manifest; they must be registered at runtime.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // Explicitly NOT_EXPORTED. Screen on/off are protected system broadcasts, so Android 14+'s
        // mandatory-flag rule technically exempts them, but being explicit costs nothing and removes
        // any doubt as the target SDK moves.
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        receiver = screenReceiver

        // The screen is usually already on when the listener binds (the user just granted access, or
        // the app was updated), and waiting for the next unlock would leave a dead window.
        if (isScreenOn(appContext)) start(scope)
    }

    fun detach(context: Context) {
        stop()
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun isScreenOn(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(false)

    private fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (!settings.remoteActionsEnabled.first()) return@launch
                // The always-on service already holds a poll; a second consumer would only trade 409s.
                if (TelegramListenerService.isRunning) return@launch
                // A failed cycle (offline, unpaired, another consumer) backs off instead of hot-looping.
                if (!poller.pollOnce()) delay(BACKOFF_MS)
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
    }

    private companion object {
        const val BACKOFF_MS = 15_000L
    }
}
