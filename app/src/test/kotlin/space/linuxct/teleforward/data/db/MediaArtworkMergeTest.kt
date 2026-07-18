package space.linuxct.teleforward.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.data.db.dao.OutboxDao
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageKind
import space.linuxct.teleforward.data.db.entity.OutboxStatus

/**
 * The fix for "every song arrives twice, once bare and once with a cover".
 *
 * A media player announces a track change immediately with no album art, then re-posts moments later
 * once the art has loaded — in one captured session, 187 of 329 media posts carried no large icon at
 * all. Both posts describe the same track, so they now share a dedupeKey and the second is dropped as
 * a duplicate. But the second is the one carrying the cover, so dropping it outright would trade
 * duplicate messages for permanently missing artwork. It is merged into the queued row instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaArtworkMergeTest {

    private lateinit var db: TeleForwardDatabase
    private lateinit var dao: OutboxDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TeleForwardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.outboxDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(status: OutboxStatus = OutboxStatus.PENDING) = OutboxEntity(
        dedupeKey = "track-1",
        packageName = "com.example.player",
        channelId = "media",
        appLabel = "Player",
        channelName = "Now playing",
        title = "Some Track",
        body = "Some Artist",
        isMedia = true,
        postTime = 1_000L,
        status = status,
        attemptCount = 0,
        nextAttemptAt = 0L,
        lastError = null,
        createdAt = 1_000L,
    )

    private fun art(path: String = "/cache/cover.jpg") = listOf(
        OutboxImageEntity(
            outboxId = 0L,
            filePath = path,
            mime = "image/jpeg",
            sizeBytes = 1234L,
            kind = OutboxImageKind.LARGE_ICON,
        ),
    )

    @Test
    fun artworkFromTheSecondPostIsMergedIntoTheQueuedRow() = runBlocking {
        val id = dao.insert(row())
        assertTrue(dao.getImages(id).isEmpty())

        // The re-post: same track, now with the cover.
        assertTrue(dao.attachImagesIfUnsent("track-1", art()))

        val images = dao.getImages(id)
        assertEquals(1, images.size)
        assertEquals("/cache/cover.jpg", images.first().filePath)
        // Still exactly one message will be sent.
        assertEquals(1, dao.getDeliverable(listOf(OutboxStatus.PENDING), 10).size)
    }

    @Test
    fun aRowAlreadyBeingSentIsLeftAlone() = runBlocking {
        // Its images were read when the send began; adding more now would attach files to a message
        // that has already gone out, and the caller needs to know so it can delete them.
        dao.insert(row(status = OutboxStatus.SENDING))
        assertFalse(dao.attachImagesIfUnsent("track-1", art()))
    }

    @Test
    fun aRowThatAlreadyHasArtworkIsNotOverwritten() = runBlocking {
        val id = dao.insert(row())
        dao.insertImages(art("/cache/first.jpg").map { it.copy(outboxId = id) })

        assertFalse(dao.attachImagesIfUnsent("track-1", art("/cache/second.jpg")))

        val images = dao.getImages(id)
        assertEquals(1, images.size)
        assertEquals("/cache/first.jpg", images.first().filePath)
    }

    @Test
    fun anUnknownTrackMergesNothing() = runBlocking {
        assertFalse(dao.attachImagesIfUnsent("no-such-track", art()))
    }

    @Test
    fun mergingNothingIsANoOp() = runBlocking {
        dao.insert(row())
        assertFalse(dao.attachImagesIfUnsent("track-1", emptyList()))
    }
}
