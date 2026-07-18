package space.linuxct.teleforward.data.telegram

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import space.linuxct.teleforward.data.db.TeleForwardDatabase
import space.linuxct.teleforward.domain.NotificationActionInfo
import space.linuxct.teleforward.domain.PLAY_PAUSE_LABEL

/**
 * The now-playing control's keyboard, exercised through the real DAO (Robolectric, no device).
 *
 * Guards the property the pure `remoteButtons` test cannot: that the *caller* asks for stable labels.
 * The transport toggle must read "⏯ Play/Pause" and stay that way — a live "Pause" is only true until
 * you press it, and the correcting edit races the press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingKeyboardTest {

    private lateinit var db: TeleForwardDatabase
    private lateinit var keyboards: RemoteActionKeyboards

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            TeleForwardDatabase::class.java,
        ).allowMainThreadQueries().build()
        keyboards = RemoteActionKeyboards(
            db.callbackTokenDao(),
            // Mirrors the DI-provided instance (di/NetworkModule).
            Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A media transport row whose middle action carries the player's current wording. */
    private fun transport(playPauseTitle: String) = listOf(
        NotificationActionInfo(index = 0, title = "Previous", immutable = true),
        NotificationActionInfo(index = 1, title = playPauseTitle, immutable = true),
        NotificationActionInfo(index = 2, title = "Next", immutable = true),
    )

    private fun buildKeyboard(playPauseTitle: String): String = runBlocking {
        requireNotNull(
            keyboards.replaceKeyboardForMessage(
                notificationKey = "0|com.example.player|1|null|10123",
                actions = transport(playPauseTitle),
                chatId = 42L,
                messageId = 7L,
                now = 1_000L,
            ),
        )
    }

    @Test
    fun transportToggleAlwaysReadsPlayPause() {
        listOf("Play", "Pause", "Play/Pause").forEach { title ->
            val markup = buildKeyboard(title)
            assertTrue(
                "expected $PLAY_PAUSE_LABEL for a player showing \"$title\", got: $markup",
                markup.contains(PLAY_PAUSE_LABEL),
            )
            // The player's own transient wording must not survive into the button.
            assertTrue(markup.contains("Previous") && markup.contains("Next"))
        }
    }

    @Test
    fun theKeyboardIsIdenticalWhetherPlayingOrPaused() {
        // Same labels in the same order, so a play ⇄ pause tick renders to nothing new. Tokens differ
        // (they are freshly minted each rebuild), so compare the button text rather than the JSON.
        assertEquals(labelsOf(buildKeyboard("Play")), labelsOf(buildKeyboard("Pause")))
    }

    /** Button texts in order, pulled out of the serialized `inline_keyboard`. */
    private fun labelsOf(markup: String): List<String> =
        Regex("\"text\":\"(.*?)\"").findAll(markup).map { it.groupValues[1] }.toList()
}
