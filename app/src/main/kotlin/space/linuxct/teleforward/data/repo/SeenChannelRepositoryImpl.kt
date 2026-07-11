package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.linuxct.teleforward.data.db.dao.SeenChannelDao
import space.linuxct.teleforward.data.db.entity.SeenChannelEntity
import space.linuxct.teleforward.domain.SeenChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SeenChannelRepository]. Maps [SeenChannelEntity] <-> [SeenChannel] and upserts a
 * channel observation via insert-ignore + touch, preserving the original `firstSeen` while
 * refreshing `name`/`importance`/`lastSeen` on repeat sightings.
 */
@Singleton
class SeenChannelRepositoryImpl @Inject constructor(
    private val seenChannelDao: SeenChannelDao,
) : SeenChannelRepository {

    override fun observeAll(): Flow<List<SeenChannel>> =
        seenChannelDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeForPackage(packageName: String): Flow<List<SeenChannel>> =
        seenChannelDao.observeForPackage(packageName).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getForPackage(packageName: String): List<SeenChannel> =
        seenChannelDao.getForPackage(packageName).map { it.toDomain() }

    override fun observeSeenPackages(): Flow<List<String>> = seenChannelDao.observeSeenPackages()

    override suspend fun getSeenPackages(): List<String> = seenChannelDao.getSeenPackages()

    override suspend fun lastSeenForPackage(packageName: String): Long? =
        seenChannelDao.lastSeenForPackage(packageName)

    override suspend fun recordSeen(
        packageName: String,
        channelId: String,
        channelName: String?,
        importance: Int?,
        userSerial: Long,
        seenAt: Long,
    ) {
        val row = SeenChannelEntity(
            packageName = packageName,
            channelId = channelId,
            name = channelName,
            importance = importance,
            userSerial = userSerial,
            firstSeen = seenAt,
            lastSeen = seenAt,
        )
        // Insert-ignore keeps the first sighting's firstSeen; touch refreshes the mutable fields.
        if (seenChannelDao.insertIgnore(row) == -1L) {
            seenChannelDao.touch(
                packageName = packageName,
                channelId = channelId,
                userSerial = userSerial,
                name = channelName,
                importance = importance,
                lastSeen = seenAt,
            )
        }
    }

    override suspend fun clear() = seenChannelDao.clear()

    private fun SeenChannelEntity.toDomain(): SeenChannel = SeenChannel(
        packageName = packageName,
        channelId = channelId,
        name = name,
        importance = importance,
        userSerial = userSerial,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
    )
}
