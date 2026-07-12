package space.linuxct.teleforward.diag

import android.app.PendingIntent
import android.os.Build
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §PI PendingIntent probe (public API only). Records the metadata a notification listener may
 * legitimately read: the creator/target package, the activity/broadcast/service/foreground-service
 * and immutability flags (API 31+), and `toString()`.
 *
 * The wrapped [android.content.Intent] is deliberately NOT read: it is unreadable cross-UID (every
 * attempt yields a `SecurityException`) and any access would depend on hidden framework APIs, which
 * violate Google Play policy. Only public getters are used here.
 */
@Singleton
class PendingIntentProbe @Inject constructor() {

    fun probe(pi: PendingIntent?, env: ProbeEnv): JSONObject? {
        if (pi == null) return null
        val o = JSONObject()
        runCatching { pi.creatorPackage?.let { o.put("creatorPackage", it); env.addCandidate(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { o.put("isActivity", pi.isActivity) }
            runCatching { o.put("isBroadcast", pi.isBroadcast) }
            runCatching { o.put("isService", pi.isService) }
            runCatching { o.put("isForegroundService", pi.isForegroundService) }
            runCatching { o.put("isImmutable", pi.isImmutable) }
        }
        @Suppress("DEPRECATION")
        runCatching { pi.targetPackage?.let { o.put("targetPackage", it) } }
        runCatching { o.put("toString", pi.toString()) }
        return o
    }
}
