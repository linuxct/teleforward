package space.linuxct.teleforward.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import space.linuxct.teleforward.data.db.dao.CallbackTokenDao
import space.linuxct.teleforward.data.db.dao.NowPlayingSessionDao
import space.linuxct.teleforward.data.db.dao.OutboxDao
import space.linuxct.teleforward.data.db.dao.PendingLinkResolutionDao
import space.linuxct.teleforward.data.db.dao.RulesDao
import space.linuxct.teleforward.data.db.dao.SeenChannelDao
import space.linuxct.teleforward.data.db.dao.SeenConversationDao
import space.linuxct.teleforward.data.db.entity.CallbackTokenEntity
import space.linuxct.teleforward.data.db.entity.NowPlayingSessionEntity
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
import space.linuxct.teleforward.data.db.entity.PendingLinkResolutionEntity
import space.linuxct.teleforward.data.db.entity.SeenChannelEntity
import space.linuxct.teleforward.data.db.entity.SeenConversationEntity
import space.linuxct.teleforward.data.db.entity.SelectionRuleEntity

@Database(
    entities = [
        SelectionRuleEntity::class,
        SeenChannelEntity::class,
        SeenConversationEntity::class,
        OutboxEntity::class,
        OutboxImageEntity::class,
        PendingLinkResolutionEntity::class,
        CallbackTokenEntity::class,
        NowPlayingSessionEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TeleForwardDatabase : RoomDatabase() {

    abstract fun rulesDao(): RulesDao

    abstract fun seenChannelDao(): SeenChannelDao

    abstract fun seenConversationDao(): SeenConversationDao

    abstract fun outboxDao(): OutboxDao

    abstract fun pendingLinkResolutionDao(): PendingLinkResolutionDao

    abstract fun callbackTokenDao(): CallbackTokenDao

    abstract fun nowPlayingSessionDao(): NowPlayingSessionDao

    companion object {
        const val DATABASE_NAME = "teleforward.db"

        /**
         * v1 → v2: adds per-conversation detection & filtering.
         *  - `selection_rules` gains a nullable `conversationId` column; its unique index widens from
         *    `(packageName, channelId)` to `(packageName, channelId, conversationId)`. Existing rows
         *    keep `conversationId = NULL` (whole-app/channel rules), so all prior selections survive.
         *  - new `seen_conversations` catalog table.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `selection_rules` ADD COLUMN `conversationId` TEXT",
                )
                db.execSQL("DROP INDEX IF EXISTS `index_selection_rules_packageName_channelId`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_selection_rules_packageName_channelId_conversationId` " +
                        "ON `selection_rules` (`packageName`, `channelId`, `conversationId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seen_conversations` (" +
                        "`packageName` TEXT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`title` TEXT, " +
                        "`userSerial` INTEGER NOT NULL, " +
                        "`firstSeen` INTEGER NOT NULL, " +
                        "`lastSeen` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`packageName`, `channelId`, `conversationId`, `userSerial`))",
                )
            }
        }

        /**
         * v2 → v3: adds the nullable `youtubeChannelId` column to `outbox`, used by the "magic link"
         * reconstruction to rebuild a YouTube video url from the channel id + title. Existing rows keep
         * `NULL` (non-YouTube / pre-feature), so all queued items survive.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `youtubeChannelId` TEXT")
            }
        }

        /**
         * v3 → v4: adds the nullable `extractedLinks` column to `outbox` (Tier-0 link harvest), storing
         * every `http`/`https` link found in the notification, newline-joined. Existing rows keep
         * `NULL` (no harvested links), so all queued items survive.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `extractedLinks` TEXT")
            }
        }

        /**
         * v4 → v5: adds the `pending_link_resolution` table backing the magic-link edit-after-send
         * retry (an item forwarded without a link is re-resolved in the background and the sent
         * message edited to append it). New table only — no existing rows are touched. The DDL mirrors
         * Room's generated schema exactly (column order/types, autoincrement PK, `nextAttemptAt` index)
         * so Room's on-open validation passes.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_link_resolution` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, " +
                        "`isCaption` INTEGER NOT NULL, " +
                        "`sentText` TEXT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, " +
                        "`videoTitle` TEXT NOT NULL, " +
                        "`attemptCount` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_pending_link_resolution_nextAttemptAt` " +
                        "ON `pending_link_resolution` (`nextAttemptAt`)",
                )
            }
        }

        /**
         * v5 → v6: adds two nullable `outbox` columns backing WhatsApp magic-link reconstruction —
         * `conversationId` (the chat shortcut id, e.g. `…@s.whatsapp.net` / `…@lid`) and
         * `senderContactUri` (the 1:1 sender's `content://com.android.contacts/…` uri). Existing rows
         * keep `NULL` (non-conversation / pre-feature), so all queued items survive.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `conversationId` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `senderContactUri` TEXT")
            }
        }

        /**
         * v6 → v7: adds the nullable `youtubeVideoId` column to `outbox`. Live-stream and premiere
         * notifications key themselves by the VIDEO id (not the channel id), so the watch url can be
         * built directly with no feed/search lookup. Existing rows keep `NULL` (uploads / pre-feature),
         * so all queued items survive.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `youtubeVideoId` TEXT")
            }
        }

        /**
         * v7 → v8: adds the two nullable `outbox` columns backing remote notification actions —
         * `notificationKey` (`StatusBarNotification.key`, so a Telegram button press can find the
         * still-posted notification) and `actionsJson` (its action buttons as compact metadata).
         * Existing rows keep `NULL` (pre-feature), so all queued items survive.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `notificationKey` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `actionsJson` TEXT")
            }
        }

        /**
         * v8 → v9: adds the `callback_tokens` table backing Telegram inline buttons — it maps a
         * button's 64-byte-capped `callback_data` token back to the device notification + action to
         * fire, and records which message a button belongs to. New table only; no existing rows are
         * touched. The DDL mirrors Room's generated schema exactly (column order/types, autoincrement
         * PK, all three indices) so Room's on-open validation passes.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `callback_tokens` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`token` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`notificationKey` TEXT NOT NULL, " +
                        "`actionIndex` INTEGER NOT NULL, " +
                        "`semantic` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`messageId` INTEGER, " +
                        "`outboxId` INTEGER NOT NULL, " +
                        "`expiresAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_callback_tokens_token` " +
                        "ON `callback_tokens` (`token`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_callback_tokens_chatId_messageId` " +
                        "ON `callback_tokens` (`chatId`, `messageId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_callback_tokens_expiresAt` " +
                        "ON `callback_tokens` (`expiresAt`)",
                )
            }
        }

        /**
         * v9 → v10: adds `now_playing_sessions`, which remembers the one Telegram message kept per
         * media app so it can be edited in place as the track changes (rather than forwarding a new
         * message on every track change and play/pause). New table only; no existing rows are touched.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `now_playing_sessions` (" +
                        "`sessionKey` TEXT PRIMARY KEY NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`messageId` INTEGER NOT NULL, " +
                        "`notificationKey` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v10 → v11: adds the nullable `trackKey` column to `now_playing_sessions`, separating "the
         * song changed" (a fresh message, so Telegram notifies) from "playback state changed" (a
         * silent edit). Existing rows keep `NULL`, which simply forces one fresh message on the next
         * update — nothing is lost.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `now_playing_sessions` ADD COLUMN `trackKey` TEXT")
            }
        }

        /**
         * v11 → v12: adds the nullable `photoMessage` column to `now_playing_sessions`. The control
         * now carries album art, so a state-only change (play ⇄ pause) must edit the message's caption
         * rather than its text. Existing rows keep `NULL` (treated as a text message), and the next
         * track change replaces the message anyway.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `now_playing_sessions` ADD COLUMN `photoMessage` INTEGER")
            }
        }
    }
}
