package space.linuxct.teleforward.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the upgrade path for existing installs.
 *
 * A missing migration is survivable (Room falls back to a destructive rebuild), but a migration that
 * leaves the schema *subtly different* from what Room expects is not: Room throws on open and the
 * fallback does NOT rescue it. These tests execute the real migrations and assert the resulting
 * schema, rather than trusting the DDL by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationChainTest {

    /** Every migration, in order — the exact set registered in `DatabaseModule`. */
    private val chain: List<Migration> = listOf(
        TeleForwardDatabase.MIGRATION_1_2,
        TeleForwardDatabase.MIGRATION_2_3,
        TeleForwardDatabase.MIGRATION_3_4,
        TeleForwardDatabase.MIGRATION_4_5,
        TeleForwardDatabase.MIGRATION_5_6,
        TeleForwardDatabase.MIGRATION_6_7,
        TeleForwardDatabase.MIGRATION_7_8,
        TeleForwardDatabase.MIGRATION_8_9,
        TeleForwardDatabase.MIGRATION_9_10,
        TeleForwardDatabase.MIGRATION_10_11,
        TeleForwardDatabase.MIGRATION_11_12,
        TeleForwardDatabase.MIGRATION_12_13,
    )

    @Test
    fun chainIsContiguousFromV1ToCurrent() {
        // Any gap here means an install on that version cannot upgrade and gets wiped by the
        // destructive fallback.
        assertEquals(1, chain.first().startVersion)
        assertEquals(DATABASE_VERSION, chain.last().endVersion)
        chain.zipWithNext { previous, next ->
            assertEquals(
                "gap between v${previous.endVersion} and v${next.startVersion}",
                previous.endVersion,
                next.startVersion,
            )
        }
    }

    @Test
    fun mediaForwardsTableAndIndicesAreCreated() {
        // Room validates a hand-written migration's DDL against its own generated schema on open, and
        // a mismatch throws rather than falling back — so the table this creates has to be exact.
        val db = openBlank()
        // 12→13 also ALTERs outbox, so that table has to exist for the migration to run at all.
        db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        TeleForwardDatabase.MIGRATION_12_13.migrate(db)

        // The new outbox flag must be nullable so rows queued before the upgrade survive it.
        val outbox = columnsNotNull(db, "outbox")
        assertTrue("isMedia should exist", outbox.containsKey("isMedia"))
        assertEquals("isMedia must be nullable", false, outbox.getValue("isMedia"))

        val columns = columnsNotNull(db, "media_forwards")
        assertEquals(
            setOf("id", "packageName", "chatId", "messageId", "trackKey", "photoMessage", "sentAt"),
            columns.keys,
        )
        // trackKey is the only nullable column; everything else is required.
        assertEquals("trackKey must be nullable", false, columns.getValue("trackKey"))
        assertEquals("packageName must be NOT NULL", true, columns.getValue("packageName"))

        assertEquals(
            setOf(
                "index_media_forwards_packageName",
                "index_media_forwards_chatId_messageId",
                "index_media_forwards_sentAt",
            ),
            indexNames(db, "media_forwards"),
        )
        db.close()
    }

    @Test
    fun outboxColumnsAddedByMigrationsAreAllNullable() {
        // Every outbox column added since v1 is nullable, which is what lets existing queued rows
        // survive each upgrade untouched.
        val db = openBlank()
        db.execSQL("CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        listOf(
            TeleForwardDatabase.MIGRATION_2_3,
            TeleForwardDatabase.MIGRATION_3_4,
            TeleForwardDatabase.MIGRATION_5_6,
            TeleForwardDatabase.MIGRATION_6_7,
            TeleForwardDatabase.MIGRATION_7_8,
        ).forEach { it.migrate(db) }

        val notNull = columnsNotNull(db, "outbox")
        listOf(
            "youtubeChannelId", "extractedLinks", "conversationId", "senderContactUri",
            "youtubeVideoId", "notificationKey", "actionsJson",
        ).forEach { column ->
            assertTrue("$column should exist", notNull.containsKey(column))
            assertEquals("$column must be nullable", false, notNull[column])
        }
        db.close()
    }

    @Test
    fun v10SessionsTableUpgradesToTheCurrentSchema() {
        // An install that created now_playing_sessions at v10 must end up with exactly the columns a
        // fresh install has — trackKey and photoMessage are added by later ALTERs, not by the CREATE.
        val db = openBlank()
        TeleForwardDatabase.MIGRATION_9_10.migrate(db)
        val atV10 = columnsNotNull(db, "now_playing_sessions")
        assertEquals("trackKey must NOT exist before 10→11", false, atV10.containsKey("trackKey"))
        assertEquals("photoMessage must NOT exist before 11→12", false, atV10.containsKey("photoMessage"))

        TeleForwardDatabase.MIGRATION_10_11.migrate(db)
        TeleForwardDatabase.MIGRATION_11_12.migrate(db)

        val current = columnsNotNull(db, "now_playing_sessions")
        assertEquals(
            setOf(
                "sessionKey", "chatId", "messageId", "notificationKey",
                "trackKey", "photoMessage", "fingerprint", "updatedAt",
            ),
            current.keys,
        )
        // Both nullable, so rows written earlier carry NULL and simply get one fresh message.
        assertEquals(false, current["trackKey"])
        assertEquals(false, current["photoMessage"])
        db.close()
    }

    @Test
    fun callbackTokensTableAndIndicesAreCreated() {
        val db = openBlank()
        TeleForwardDatabase.MIGRATION_8_9.migrate(db)
        assertTrue(columnsNotNull(db, "callback_tokens").containsKey("token"))
        val indices = indexNames(db, "callback_tokens")
        // Index names must match Room's convention exactly or on-open validation fails.
        assertTrue(indices.contains("index_callback_tokens_token"))
        assertTrue(indices.contains("index_callback_tokens_chatId_messageId"))
        assertTrue(indices.contains("index_callback_tokens_expiresAt"))
        db.close()
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun openBlank(): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(RuntimeEnvironment.getApplication())
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    /** column name -> notNull, straight from SQLite. */
    private fun columnsNotNull(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            val notNullIdx = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) {
                out[cursor.getString(nameIdx)] = cursor.getInt(notNullIdx) == 1
            }
        }
        return out
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val out = HashSet<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) out += cursor.getString(nameIdx)
        }
        return out
    }

    private companion object {
        /** Keep in step with `@Database(version = …)`. */
        const val DATABASE_VERSION = 13
    }
}
