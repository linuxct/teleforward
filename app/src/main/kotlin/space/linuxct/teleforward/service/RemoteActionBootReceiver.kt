package space.linuxct.teleforward.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.work.TelegramPollWorker
import javax.inject.Inject

/**
 * Puts remote actions back on their feet after a reboot or an app update.
 *
 * "Always listening" is a foreground service, and a foreground service does not survive a restart —
 * `START_STICKY` covers a process kill, not a reboot. Nothing else started it either, so the setting
 * could read *on* for weeks while nothing had polled since the phone last booted. Every button press
 * in that period reached nobody, which is indistinguishable from the app being broken, and it is
 * doubly unfortunate because "turn on Always listening" is the advice the app itself gives when a
 * press arrives too late to answer.
 *
 * Two things happen here, deliberately independent of each other:
 *  - try to bring the service back, and
 *  - schedule a poll regardless.
 *
 * The second is not a fallback for tidiness; it is the part that has to work. Android 12+ can refuse
 * a foreground-service start from the background, and the refusal is not something the user would
 * ever see. If the service did start, the poll worker notices and stands down immediately, so the
 * pairing costs nothing in the normal case.
 */
@AndroidEntryPoint
class RemoteActionBootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var workManager: WorkManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val appContext = context.applicationContext
        // Broadcast receivers get ~10s on the main thread; the settings reads are IO, so hold the
        // broadcast open rather than blocking it.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!settings.remoteActionsEnabled.first()) return@launch
                if (settings.remoteActionsAlwaysOn.first()) {
                    // Best-effort by design: start() swallows the Android 12+ refusal.
                    TelegramListenerService.start(appContext)
                }
                // Collect anything pressed while the phone was off. Harmless if the service came up.
                runCatching { TelegramPollWorker.scheduleBurst(workManager) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // An app update stops the service without any reboot, and is otherwise just as silent.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
