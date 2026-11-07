package com.nexachat.core.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * NexaChat local database for messages, inbox conversations, and the
 * optimistic-send outbox.
 *
 * <p>Room exports one schema JSON per version under {@code app/schemas}. Those
 * files are consumed by the instrumented migration tests, so migrations stay
 * explicit SQL rather than generated auto-migrations.
 */
@Database(
        entities = {
            MessageEntity.class,
            ConversationEntity.class,
            OutboxEntity.class,
            MessageReactionEntity.class,
            PinnedMessageEntity.class
        },
        version = 7,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MessageDao messageDao();
    public abstract ConversationDao conversationDao();
    public abstract OutboxDao outboxDao();
    public abstract ReactionDao reactionDao();
    public abstract PinDao pinDao();

    /** Additive defaulted attachment column; the default matches OutboxEntity. */
    public static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''");
                }
            };

    /** Adds embedded reply previews and the two message-interaction tables. */
    public static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
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

    /** Client-only peer read watermark, monotonically advanced from events. */
    public static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN peerReadSeq INTEGER NOT NULL DEFAULT 0");
                }
            };

    /** Nullable group avatar URL, filled by the next inbox refresh. */
    public static final Migration MIGRATION_4_5 =
            new Migration(4, 5) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN avatarUrl TEXT");
                }
            };

    /** Nullable member role used for client affordance gating. */
    public static final Migration MIGRATION_5_6 =
            new Migration(5, 6) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN myRole TEXT");
                }
            };

    /** Adds message edit/delete state and direct-peer inbox fields. */
    public static final Migration MIGRATION_6_7 =
            new Migration(6, 7) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN editedAtEpochMs INTEGER");
                    db.execSQL("ALTER TABLE messages ADD COLUMN deletedAtEpochMs INTEGER");
                    db.execSQL("ALTER TABLE conversations ADD COLUMN otherUserId TEXT");
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN lastMessageDeleted INTEGER NOT NULL DEFAULT 0");
                }
            };
}
