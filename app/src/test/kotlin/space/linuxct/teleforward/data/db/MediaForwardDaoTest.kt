package space.linuxct.teleforward.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.data.db.dao.MediaForwardDao
import space.linuxct.teleforward.data.db.entity.MediaForwardEntity

/**
 * Retention rules for media forwards: one live message per player, everything before it deleted.
 *
 * These pin the two decisions that are easy to get subtly wrong — that a single forward spanning
 * several messages doesn't delete its own other halves, and that a second player's control isn't
 * collected when the first one changes track.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaForwardDaoTest {

    private lateinit var db: TeleForwardDatabase
    private lateinit var dao: MediaForwardDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TeleForwardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.mediaForwardDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun record(
        messageId: Long,
        sentAt: Long,
        packageName: String = "com.example.player",
        chatId: Long = 42L,
    ) = dao.insert(
        MediaForwardEntity(
            packageName = packageName,
            chatId = chatId,
            messageId = messageId,
            trackKey = "track-$messageId",
            photoMessage = true,
            sentAt = sentAt,
        ),
    )

    @Test
    fun everythingOlderThanTheNewestSendIsSuperseded() = runBlocking {
        record(messageId = 1L, sentAt = 1_000L)
        record(messageId = 2L, sentAt = 2_000L)
        record(messageId = 3L, sentAt = 3_000L)

        val stale = dao.findSuperseded("com.example.player", keepFrom = 3_000L)
        // Oldest first, so a backlog collapses in a sensible order.
        assertEquals(listOf(1L, 2L), stale.map { it.messageId })
    }

    @Test
    fun aSendSpanningSeveralMessagesDoesNotDeleteItsOwnHalves() = runBlocking {
        // A media group can't carry buttons, so one forward becomes a photo message plus a text
        // message — both stamped with the same send time. Keying retention on the row id instead of
        // the timestamp would have deleted whichever half wasn't nominated as the survivor.
        record(messageId = 10L, sentAt = 5_000L)
        record(messageId = 11L, sentAt = 5_000L)

        assertTrue(dao.findSuperseded("com.example.player", keepFrom = 5_000L).isEmpty())
    }

    @Test
    fun anotherPlayersControlIsLeftAlone() = runBlocking {
        record(messageId = 1L, sentAt = 1_000L, packageName = "com.example.player")
        record(messageId = 2L, sentAt = 1_000L, packageName = "com.other.video")

        // The music app advancing a track must not collect the video app's transport controls.
        record(messageId = 3L, sentAt = 9_000L, packageName = "com.example.player")
        val stale = dao.findSuperseded("com.example.player", keepFrom = 9_000L)
        assertEquals(listOf(1L), stale.map { it.messageId })
    }

    @Test
    fun rowsTooOldToDeleteAreDropped() = runBlocking {
        // Telegram refuses to let a bot delete its own messages past ~48h, so keeping these would only
        // guarantee a failing delete attempt on every future track.
        record(messageId = 1L, sentAt = 1_000L)
        record(messageId = 2L, sentAt = 90_000L)

        dao.deleteOlderThan(cutoff = 50_000L)
        assertEquals(listOf(2L), dao.findSuperseded("com.example.player", keepFrom = 99_999L).map { it.messageId })
    }

    @Test
    fun ourOwnMessagesAreRecognisable() = runBlocking {
        // This is what stops the pin-notice cleanup from deleting a notice for a pin the USER made.
        record(messageId = 7L, sentAt = 1_000L, chatId = 42L)
        assertEquals(1, dao.countByMessage(chatId = 42L, messageId = 7L))
        assertEquals(0, dao.countByMessage(chatId = 42L, messageId = 8L))
        assertEquals(0, dao.countByMessage(chatId = 99L, messageId = 7L))
    }

}
