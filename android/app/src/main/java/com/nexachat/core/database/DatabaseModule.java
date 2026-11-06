package com.nexachat.core.database;

import android.content.Context;
import androidx.room.Room;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

/**
 * Room providers. The database file name is explicit so debug inspection and
 * backup rules can point at one stable artifact.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class DatabaseModule {
    private DatabaseModule() {}

    @Provides
    @Singleton
    public static AppDatabase provideAppDatabase(@ApplicationContext Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "nexachat.db")
                .addMigrations(
                        AppDatabase.Companion.getMIGRATION_1_2(),
                        AppDatabase.Companion.getMIGRATION_2_3(),
                        AppDatabase.Companion.getMIGRATION_3_4(),
                        AppDatabase.Companion.getMIGRATION_4_5(),
                        AppDatabase.Companion.getMIGRATION_5_6(),
                        AppDatabase.Companion.getMIGRATION_6_7())
                .build();
    }

    @Provides
    @Singleton
    public static MessageDao provideMessageDao(AppDatabase database) {
        return database.messageDao();
    }

    @Provides
    @Singleton
    public static ConversationDao provideConversationDao(AppDatabase database) {
        return database.conversationDao();
    }

    @Provides
    @Singleton
    public static OutboxDao provideOutboxDao(AppDatabase database) {
        return database.outboxDao();
    }

    @Provides
    @Singleton
    public static ReactionDao provideReactionDao(AppDatabase database) {
        return database.reactionDao();
    }

    @Provides
    @Singleton
    public static PinDao providePinDao(AppDatabase database) {
        return database.pinDao();
    }
}
