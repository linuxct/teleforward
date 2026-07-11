package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.domain.SeenChannel

/**
 * The catalog of channels (and, transitively, apps) discovered from observed notifications.
 * This is the only source of channel enumeration (a listener cannot enumerate channels up front).
 */
interface SeenChannelRepository {

    fun observeAll(): Flow<List<SeenChannel>>

    fun observeForPackage(packageName: String): Flow<List<SeenChannel>>

    suspend fun getForPackage(packageName: String): List<SeenChannel>

    fun observeSeenPackages(): Flow<List<String>>

    suspend fun getSeenPackages(): List<String>

    suspend fun lastSeenForPackage(packageName: String): Long?

    /** Upsert a channel observation (called on every matched notification). */
    suspend fun recordSeen(
        packageName: String,
        channelId: String,
        channelName: String?,
        importance: Int?,
        userSerial: Long,
        seenAt: Long,
    )

    suspend fun clear()
}
