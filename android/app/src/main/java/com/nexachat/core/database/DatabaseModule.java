package com.nexachat.core.database;

import android.content.Context;
import androidx.room.Room;
import com.nexachat.core.storage.TokenCipher;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

/**
 * Room providers. The database file name is explicit so debug inspection and
 * backup rules can point at one stable artifact.
 *
 * <p>The database is opened through SQLCipher (audit F-01): the passphrase is sealed
 * with the existing keystore {@link TokenCipher} and a plaintext legacy database is
 * migrated to encrypted before Room opens it — see {@link DatabaseEncryption}.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class DatabaseModule {
    private DatabaseModule() {}

    @Provides
    @Singleton
    public static AppDatabase provideAppDatabase(
            @ApplicationContext Context context, TokenCipher cipher) {
        byte[] passphrase = new DatabasePassphraseStore(context, cipher).passphrase();
        return Room.databaseBuilder(context, AppDatabase.class, DatabaseEncryption.DB_NAME)
                .openHelperFactory(DatabaseEncryption.encryptedFactory(context, passphrase))
                .addMigrations(
                        AppDatabase.MIGRATION_1_2,
                        AppDatabase.MIGRATION_2_3,
                        AppDatabase.MIGRATION_3_4,
                        AppDatabase.MIGRATION_4_5,
                        AppDatabase.MIGRATION_5_6,
                        AppDatabase.MIGRATION_6_7,
                        AppDatabase.MIGRATION_7_8,
                        AppDatabase.MIGRATION_8_9,
                        AppDatabase.MIGRATION_9_10,
                        AppDatabase.MIGRATION_10_11,
                        AppDatabase.MIGRATION_11_12,
                        AppDatabase.MIGRATION_12_13,
                        AppDatabase.MIGRATION_13_14,
                        AppDatabase.MIGRATION_14_15,
                        AppDatabase.MIGRATION_15_16,
                        AppDatabase.MIGRATION_16_17,
                        AppDatabase.MIGRATION_17_18,
                        AppDatabase.MIGRATION_18_19,
                        AppDatabase.MIGRATION_19_20,
                        AppDatabase.MIGRATION_20_21)
                .addCallback(AppDatabase.SYNC_CACHE_CALLBACK)
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
    public static StarDao provideStarDao(AppDatabase database) {
        return database.starDao();
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

    @Provides
    @Singleton
    public static UserDao provideUserDao(AppDatabase database) {
        return database.userDao();
    }

    @Provides
    @Singleton
    public static CallLogDao provideCallLogDao(AppDatabase database) {
        return database.callLogDao();
    }

    @Provides
    @Singleton
    public static OfflineOutboxDao provideOfflineOutboxDao(AppDatabase database) {
        return database.offlineOutboxDao();
    }

    @Provides
    @Singleton
    public static KeysetTrustDao provideKeysetTrustDao(AppDatabase database) {
        return database.keysetTrustDao();
    }

    @Provides
    @Singleton
    public static MessageTranslationDao provideMessageTranslationDao(AppDatabase database) {
        return database.messageTranslationDao();
    }
}
