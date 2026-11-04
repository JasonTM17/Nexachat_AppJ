package com.nexachat.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room providers. The database file name is explicit (`nexachat.db`) so
 * debug-inspection and backup rules can point at one stable artifact.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    @Singleton
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideConversationDao(database: AppDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    @Singleton
    fun provideOutboxDao(database: AppDatabase): OutboxDao = database.outboxDao()

    @Provides
    @Singleton
    fun provideReactionDao(database: AppDatabase): ReactionDao = database.reactionDao()

    @Provides
    @Singleton
    fun providePinDao(database: AppDatabase): PinDao = database.pinDao()

    private const val DATABASE_NAME = "nexachat.db"
}
