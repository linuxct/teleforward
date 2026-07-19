package space.linuxct.teleforward.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.linuxct.teleforward.data.db.TeleForwardDatabase
import space.linuxct.teleforward.data.db.dao.CallbackTokenDao
import space.linuxct.teleforward.data.db.dao.MediaForwardDao
import space.linuxct.teleforward.data.db.dao.NowPlayingSessionDao
import space.linuxct.teleforward.data.db.dao.OutboxDao
import space.linuxct.teleforward.data.db.dao.PendingLinkResolutionDao
import space.linuxct.teleforward.data.db.dao.RulesDao
import space.linuxct.teleforward.data.db.dao.SeenChannelDao
import space.linuxct.teleforward.data.db.dao.SeenConversationDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TeleForwardDatabase =
        Room.databaseBuilder(
            context,
            TeleForwardDatabase::class.java,
            TeleForwardDatabase.DATABASE_NAME,
        )
            // Real migration preserves existing user selections; destructive fallback stays as a
            // last-resort safety net only (the migration should make it unnecessary).
            .addMigrations(
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
                TeleForwardDatabase.MIGRATION_13_14,
                TeleForwardDatabase.MIGRATION_14_15,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideRulesDao(database: TeleForwardDatabase): RulesDao = database.rulesDao()

    @Provides
    fun provideSeenChannelDao(database: TeleForwardDatabase): SeenChannelDao =
        database.seenChannelDao()

    @Provides
    fun provideSeenConversationDao(database: TeleForwardDatabase): SeenConversationDao =
        database.seenConversationDao()

    @Provides
    fun provideOutboxDao(database: TeleForwardDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun providePendingLinkResolutionDao(database: TeleForwardDatabase): PendingLinkResolutionDao =
        database.pendingLinkResolutionDao()

    @Provides
    fun provideCallbackTokenDao(database: TeleForwardDatabase): CallbackTokenDao =
        database.callbackTokenDao()

    @Provides
    fun provideNowPlayingSessionDao(database: TeleForwardDatabase): NowPlayingSessionDao =
        database.nowPlayingSessionDao()

    @Provides
    fun provideMediaForwardDao(database: TeleForwardDatabase): MediaForwardDao =
        database.mediaForwardDao()
}
