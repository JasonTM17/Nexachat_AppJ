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
            PinnedMessageEntity.class,
            UserEntity.class,
            CallLogEntity.class,
            OfflineOutboxEntity.class,
            SyncCursorEntity.class
        },
        version = 16,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MessageDao messageDao();
    public abstract ConversationDao conversationDao();
    public abstract OutboxDao outboxDao();
    public abstract ReactionDao reactionDao();
    public abstract PinDao pinDao();
    public abstract UserDao userDao();
    public abstract CallLogDao callLogDao();
    public abstract OfflineOutboxDao offlineOutboxDao();
    public abstract SyncCursorDao syncCursorDao();

    /** Clears cached account data and other offline actions while retaining message sends. */
    public void clearAccountDataPreservingOutbox() {
        SupportSQLiteDatabase db = getOpenHelper().getWritableDatabase();
        runInTransaction(() -> {
            db.execSQL("DELETE FROM `messages`");
            db.execSQL("DELETE FROM `conversations`");
            db.execSQL("DELETE FROM `message_reactions`");
            db.execSQL("DELETE FROM `pinned_messages`");
            db.execSQL("DELETE FROM `users`");
            db.execSQL("DELETE FROM `call_logs`");
            db.execSQL("DELETE FROM `offline_outbox`");
            db.execSQL("DELETE FROM `sync_cursors`");
        });
    }

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

    /**
     * Adds users, call_logs, offline_outbox tables and peerDeliveredSeq to conversations.
     */
    public static final Migration MIGRATION_7_8 =
            new Migration(7, 8) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `users` ("
                                    + "`id` TEXT NOT NULL, `username` TEXT, `displayName` TEXT, "
                                    + "`phoneNumber` TEXT, `avatarUrl` TEXT, `bio` TEXT, "
                                    + "`lastSeenAt` INTEGER, `updatedAt` INTEGER, "
                                    + "PRIMARY KEY(`id`))");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_users_displayName` "
                                    + "ON `users` (`displayName`)");

                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `call_logs` ("
                                    + "`callId` TEXT NOT NULL, `conversationId` TEXT, `peerId` TEXT, "
                                    + "`peerDisplayName` TEXT, `peerAvatarUrl` TEXT, `direction` TEXT, "
                                    + "`callType` TEXT, `startedAt` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, "
                                    + "`endReason` TEXT, PRIMARY KEY(`callId`))");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_call_logs_startedAt` "
                                    + "ON `call_logs` (`startedAt`)");

                    db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `offline_outbox` ("
                                    + "`id` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, "
                                    + "`actionType` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, "
                                    + "`state` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, "
                                    + "`createdAtEpochMs` INTEGER NOT NULL, `lastAttemptEpochMs` INTEGER NOT NULL, "
                                    + "PRIMARY KEY(`id`))");
                    db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_outbox_idempotencyKey` "
                                    + "ON `offline_outbox` (`idempotencyKey`)");

                    db.execSQL(
                            "ALTER TABLE `conversations` ADD COLUMN `peerDeliveredSeq` INTEGER NOT NULL DEFAULT 0");
                }
            };

    /**
     * Adds the caller-scoped conversation preferences: mute (with optional
     * expiry), archive and per-conversation AI opt-out.
     */
    public static final Migration MIGRATION_8_9 =
            new Migration(8, 9) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN muted INTEGER NOT NULL DEFAULT 0");
                    db.execSQL("ALTER TABLE conversations ADD COLUMN mutedUntilEpochMs INTEGER");
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN aiOptOut INTEGER NOT NULL DEFAULT 0");
                }
            };

    /** Adds conversation pin state (pinned boolean, pinnedAt epoch ms). */
    public static final Migration MIGRATION_9_10 =
            new Migration(9, 10) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
                    db.execSQL(
                            "ALTER TABLE conversations ADD COLUMN pinnedAt INTEGER");
                }
            };

    /** Persists structured mention metadata for sent and pending messages. */
    public static final Migration MIGRATION_10_11 =
            new Migration(10, 11) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE messages ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'");
                    db.execSQL(
                            "ALTER TABLE messages ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0");
                    db.execSQL(
                            "ALTER TABLE outbox ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'");
                    db.execSQL(
                            "ALTER TABLE outbox ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0");
                }
            };

    /** Caches the authenticated caller's label ids beside each inbox row. */
    public static final Migration MIGRATION_11_12 =
            new Migration(11, 12) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE `conversations` ADD COLUMN `labelIdsCsv` TEXT NOT NULL DEFAULT ''");
                }
            };

    /** Scopes queued optimistic messages to the account that created them. */
    public static final Migration MIGRATION_12_13 =
            new Migration(12, 13) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE `outbox` ADD COLUMN `ownerUserId` TEXT NOT NULL DEFAULT ''");
                    db.execSQL(
                            "ALTER TABLE `outbox` ADD COLUMN `ownerResolved` INTEGER NOT NULL DEFAULT 0");
                    db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_outbox_ownerUserId_state_createdAt` "
                                    + "ON `outbox` (`ownerUserId`, `state`, `createdAt`)");
                }
            };
    /** No history/outbox rebuild: old rows retain identity and start unversioned. */
    public static final Migration MIGRATION_13_14 = new Migration(13, 14) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `changeVersion` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_cursors` ("
                    + "`ownerUserId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                    + "`generation` INTEGER NOT NULL, `changeCursor` INTEGER NOT NULL, "
                    + "`reactionSince` TEXT, `reactionCursor` INTEGER, `reactionBaseline` TEXT, "
                    + "`rebuildingReactions` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`ownerUserId`, `conversationId`, `generation`))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_cursors_ownerUserId_conversationId` "
                    + "ON `sync_cursors` (`ownerUserId`, `conversationId`)");
            installSyncCacheTriggers(db);
        }
    };

    /** E2EE flags: plaintext rows keep 0; decrypt failures mark existing rows as unknown. */
    public static final Migration MIGRATION_14_15 = new Migration(14, 15) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `decryptFailed` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyToEncrypted` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `e2eeEnabled` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMessageEncrypted` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Preview type column: pre-migration rows stay null and keep raw snippets. */
    public static final Migration MIGRATION_15_16 = new Migration(15, 16) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `lastMessageType` TEXT");
        }
    };

    /** Cache deletion must invalidate even a numerically plausible cursor, including partial loss. */
    static void installSyncCacheTriggers(SupportSQLiteDatabase db) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS `sync_messages_cache_loss` AFTER DELETE ON `messages` "
                + "BEGIN DELETE FROM `sync_cursors` WHERE `conversationId` = OLD.`conversationId`; END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS `sync_conversation_cache_loss` AFTER DELETE ON `conversations` "
                + "BEGIN DELETE FROM `sync_cursors` WHERE `conversationId` = OLD.`id`; END");
    }

    public static final Callback SYNC_CACHE_CALLBACK = new Callback() {
        @Override public void onCreate(SupportSQLiteDatabase db) { installSyncCacheTriggers(db); }
        @Override public void onOpen(SupportSQLiteDatabase db) { installSyncCacheTriggers(db); }
    };
}
