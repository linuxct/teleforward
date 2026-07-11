package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.linuxct.teleforward.data.db.dao.SeenConversationDao
import space.linuxct.teleforward.data.db.entity.SeenConversationEntity
import space.linuxct.teleforward.domain.SeenConversation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [SeenConversationRepository]. Maps [SeenConversationEntity] <-> [SeenConversation] and
 * upserts a conversation observation via insert-ignore + touch, preserving the original `firstSeen`
 * while refreshing `title`/`lastSeen` on repeat sightings (a null title never overwrites a resolved
 * one — see [SeenConversationDao.touch]).
 */
@Singleton
class SeenConversationRepositoryImpl @Inject constructor(
    private val seenConversationDao: SeenConversationDao,
) : SeenConversationRepository {

    override fun observeForPackage(packageName: String): Flow<List<SeenConversation>> =
        seenConversationDao.observeForPackage(packageName).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getForPackage(packageName: String): List<SeenConversation> =
        seenConversationDao.getForPackage(packageName).map { it.toDomain() }

    override suspend fun recordSeen(
        packageName: String,
        channelId: String,
        conversationId: String,
        title: String?,
        userSerial: Long,
        seenAt: Long,
    ) {
        val row = SeenConversationEntity(
            packageName = packageName,
            channelId = channelId,
            conversationId = conversationId,
            title = title,
            userSerial = userSerial,
            firstSeen = seenAt,
            lastSeen = seenAt,
        )
        // Insert-ignore keeps the first sighting's firstSeen; touch refreshes the mutable fields.
        if (seenConversationDao.insertIgnore(row) == -1L) {
            seenConversationDao.touch(
                packageName = packageName,
                channelId = channelId,
                conversationId = conversationId,
                userSerial = userSerial,
                title = title,
                lastSeen = seenAt,
            )
        }
    }

    override suspend fun clear() = seenConversationDao.clear()

    private fun SeenConversationEntity.toDomain(): SeenConversation = SeenConversation(
        packageName = packageName,
        channelId = channelId,
        conversationId = conversationId,
        title = title,
        userSerial = userSerial,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
    )
}
