package space.linuxct.teleforward.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.data.db.dao.PendingLinkResolutionDao
import space.linuxct.teleforward.data.db.entity.PendingLinkResolutionEntity

/**
 * In-memory Room test for [PendingLinkResolutionDao], focused on the `getDue` due-selection +
 * ordering used by the retry worker. Runs on the JVM via Robolectric (no device/emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingLinkResolutionDaoTest {

    private lateinit var db: TeleForwardDatabase
    private lateinit var dao: PendingLinkResolutionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TeleForwardDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.pendingLinkResolutionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        nextAttemptAt: Long,
        expiresAt: Long = Long.MAX_VALUE,
        channelId: String = "UCg40OxZ1GYh3u3jBntB6DLg",
    ) = PendingLinkResolutionEntity(
        chatId = 42L,
        messageId = 7L,
        isCaption = false,
        sentText = "body",
        channelId = channelId,
        videoTitle = "A video",
        attemptCount = 0,
        nextAttemptAt = nextAttemptAt,
        expiresAt = expiresAt,
        createdAt = 0L,
    )

    @Test
    fun getDueReturnsOnlyRowsAtOrBeforeNowOrderedByNextAttempt() = runBlocking {
        val idLate = dao.insert(row(nextAttemptAt = 300L))
        val idEarly = dao.insert(row(nextAttemptAt = 100L))
        val idDueNow = dao.insert(row(nextAttemptAt = 200L))

        val due = dao.getDue(now = 200L, limit = 10)

        // 300 is not yet due; 100 and 200 are, soonest first.
        assertEquals(listOf(idEarly, idDueNow), due.map { it.id })
        // Sanity: the not-yet-due row exists but was excluded.
        assertEquals(3, dao.count())
        assertEquals(idLate, dao.getDue(now = 500L, limit = 10).map { it.id }.last())
    }

    @Test
    fun getDueRespectsLimit() = runBlocking {
        repeat(5) { dao.insert(row(nextAttemptAt = it.toLong())) }
        assertEquals(2, dao.getDue(now = 100L, limit = 2).size)
    }

    @Test
    fun earliestNextAttemptAndDeletes() = runBlocking {
        val id1 = dao.insert(row(nextAttemptAt = 500L))
        dao.insert(row(nextAttemptAt = 900L))
        assertEquals(500L, dao.getEarliestNextAttempt())

        dao.updateSchedule(id1, attemptCount = 3, nextAttemptAt = 1_000L)
        assertEquals(900L, dao.getEarliestNextAttempt())

        dao.deleteById(id1)
        assertEquals(1, dao.count())
    }

    @Test
    fun deleteExpiredDropsAgedRows() = runBlocking {
        dao.insert(row(nextAttemptAt = 10L, expiresAt = 50L))
        val keep = dao.insert(row(nextAttemptAt = 10L, expiresAt = 5_000L))

        dao.deleteExpired(now = 100L)

        assertEquals(1, dao.count())
        assertEquals(keep, dao.getDue(now = 100L, limit = 10).single().id)
    }

    @Test
    fun earliestNextAttemptNullWhenEmpty() = runBlocking {
        assertNull(dao.getEarliestNextAttempt())
    }
}
