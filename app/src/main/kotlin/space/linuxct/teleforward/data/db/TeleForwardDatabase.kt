package space.linuxct.teleforward.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import space.linuxct.teleforward.data.db.dao.OutboxDao
import space.linuxct.teleforward.data.db.dao.RulesDao
import space.linuxct.teleforward.data.db.dao.SeenChannelDao
import space.linuxct.teleforward.data.db.dao.SeenConversationDao
import space.linuxct.teleforward.data.db.entity.OutboxEntity
import space.linuxct.teleforward.data.db.entity.OutboxImageEntity
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
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TeleForwardDatabase : RoomDatabase() {

    abstract fun rulesDao(): RulesDao

    abstract fun seenChannelDao(): SeenChannelDao

    abstract fun seenConversationDao(): SeenConversationDao

    abstract fun outboxDao(): OutboxDao

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
    }
}
