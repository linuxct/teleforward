package space.linuxct.teleforward.service

import android.content.Context
import android.os.UserHandle
import android.os.UserManager

/**
 * Resolve a stable per-user serial for [user] via [UserManager] (multi-user / work-profile aware),
 * falling back to a hashed handle when the service or user is unavailable. Best-effort and
 * crash-free — shared by the listener (seen-channel upserts) and the mapper (RawNotification).
 */
internal fun resolveUserSerial(context: Context, user: UserHandle?): Long {
    if (user == null) return 0L
    return try {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        userManager?.getSerialNumberForUser(user) ?: user.hashCode().toLong()
    } catch (t: Throwable) {
        user.hashCode().toLong()
    }
}
