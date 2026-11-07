package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Local database, schema version 7. Migrations are explicit SQL so the checked-in
 * schema history stays the migration-test oracle.
 */
@Database(
        entities = {
            MessageEntity.class,
            ConversationEntity.class,
            OutboxEntity.class,
            MessageReactionEntity.class,
            PinnedMessageEntity.class,
        },
        version = 7,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();

    public abstract ConversationDao conversationDao();

    public abstract OutboxDao outboxDao();

    public abstract ReactionDao reactionDao();

    public abstract PinDao pinDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                    "ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToSenderName TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN replyToSnippet TEXT");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_reactions` ("
                            + "`messageId` TEXT NOT NULL, "
                            + "`conversationId` TEXT NOT NULL, "
                            + "`emoji` TEXT NOT NULL, "
                            + "`count` INTEGER NOT NULL, "
                            + "`reactedByMe` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`messageId`, `emoji`))");
            db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_reactions_conversationId` "
                            + "ON `message_reactions` (`conversationId`)");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pinned_messages` ("
                            + "`conversationId` TEXT NOT NULL, "
                            + "`messageId` TEXT NOT NULL, "
                            + "`pinnedAt` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`conversationId`, `messageId`))");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerReadSeq INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN avatarUrl TEXT");
        }
    };

    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN myRole TEXT");
        }
    };

    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE messages ADD COLUMN editedAtEpochMs INTEGER");
            db.execSQL("ALTER TABLE messages ADD COLUMN deletedAtEpochMs INTEGER");
            db.execSQL("ALTER TABLE conversations ADD COLUMN otherUserId TEXT");
            db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN lastMessageDeleted INTEGER NOT NULL DEFAULT 0");
        }
    };
}
