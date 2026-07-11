package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.domain.SeenConversation

/**
 * The catalog of conversations (individual chats) discovered from observed notifications. This is
 * the only source of conversation enumeration (a listener cannot query conversations up front).
 */
interface SeenConversationRepository {

    fun observeForPackage(packageName: String): Flow<List<SeenConversation>>

    suspend fun getForPackage(packageName: String): List<SeenConversation>

    /** Upsert a conversation observation (called on every matched conversation notification). */
    suspend fun recordSeen(
        packageName: String,
        channelId: String,
        conversationId: String,
        title: String?,
        userSerial: Long,
        seenAt: Long,
    )

    suspend fun clear()
}
