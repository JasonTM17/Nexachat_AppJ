package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented proof that the Room migration chain is real.
 *
 * <p>The exported schema must be usable by MigrationTestHelper, and hand-written
 * legacy databases must preserve their rows and gain the expected columns when
 * opened through the production migrations. The legacy DDL is intentionally
 * literal: it is a snapshot of shipped versions, not a second source of truth.
 */
@RunWith(AndroidJUnit4.class)
public final class RoomMigrationTest {
    private static final String LEGACY_DB = "nexachat-migration-legacy.db";
    private static final String EXPORTED_DB = "nexachat-migration-exported.db";
    private static final String OUTBOX_DB = "nexachat-outbox-owner-retention.db";

    /** Schema as shipped in version 1 (before any migration). */
    private static final List<String> V1_SCHEMA =
            Arrays.asList(
                    "CREATE TABLE IF NOT EXISTS `messages` ("
                            + "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                            + "`seq` INTEGER NOT NULL, `senderId` TEXT NOT NULL, "
                            + "`senderDisplayName` TEXT, `type` TEXT NOT NULL, `content` TEXT, "
                            + "`clientMessageId` TEXT, `replyToId` TEXT, `createdAt` INTEGER NOT NULL, "
                            + "`attachmentsJson` TEXT, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_seq` "
                            + "ON `messages` (`conversationId`, `seq`)",
                    "CREATE TABLE IF NOT EXISTS `conversations` ("
                            + "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT, "
                            + "`lastMessageSeq` INTEGER NOT NULL, `lastMessageContent` TEXT, "
                            + "`lastMessageSenderName` TEXT, `lastMessageAt` INTEGER, "
                            + "`unreadCount` INTEGER NOT NULL, `otherUserName` TEXT, "
                            + "`otherUserAvatar` TEXT, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `outbox` ("
                            + "`clientMessageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                            + "`type` TEXT NOT NULL, `content` TEXT NOT NULL, `replyToId` TEXT, "
                            + "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`clientMessageId`))");

    /** Schema as shipped in version 5 (everything before `myRole`). */
    private static final List<String> V5_SCHEMA = extendV1Schema();

    /** Schema as shipped in version 6 (before edit/delete + presence keys). */
    private static final List<String> V6_SCHEMA = extendV5Schema();

    /** Schema as shipped in version 7 (before users, call_logs, offline_outbox, peerDeliveredSeq). */
    private static final List<String> V7_SCHEMA = extendV6Schema();

    @Rule
    public MigrationTestHelper helper =
            new MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanUp() {
        context.deleteDatabase(LEGACY_DB);
        context.deleteDatabase(EXPORTED_DB);
        context.deleteDatabase(OUTBOX_DB);
    }

    @Test
    public void v7SchemaCreatesFromExportedJson() throws Exception {
        helper.createDatabase(EXPORTED_DB, 7).close();
    }

    @Test
    public void v8SchemaCreatesFromExportedJson() throws Exception {
        helper.createDatabase(EXPORTED_DB, 8).close();
    }

    @Test
    public void v12ToV13MigrationPreservesLegacyOutboxWithoutGuessingItsOwner() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(EXPORTED_DB, 12);
        try {
            oldDb.execSQL(
                    "INSERT INTO outbox (clientMessageId, conversationId, type, content, "
                            + "replyToId, state, createdAt, attachmentsJson, mentionsJson, mentionAll) "
                            + "VALUES ('cm-legacy', 'c-1', 'TEXT', 'queued', NULL, 'SENDING', "
                            + "100, '', '[]', 0)");
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                EXPORTED_DB, 13, true, AppDatabase.MIGRATION_12_13);
        try {
            assertEquals("legacy row remains queued", 1L,
                    singleLong(db, "SELECT COUNT(*) FROM outbox WHERE clientMessageId = 'cm-legacy'"));
            assertEquals("migration leaves legacy owner unknown", "",
                    singleString(db, "SELECT ownerUserId FROM outbox WHERE clientMessageId = 'cm-legacy'"));
            assertEquals("owner index exists", 1L,
                    singleLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                            + "AND name = 'index_outbox_ownerUserId_state_createdAt'"));
        } finally {
            db.close();
        }

        AppDatabase migrated = Room.databaseBuilder(context, AppDatabase.class, EXPORTED_DB)
                .allowMainThreadQueries()
                .build();
        try {
            migrated.outboxDao().assignLegacyOwner("restored-user").blockingAwait();
            assertEquals(1, migrated.outboxDao().pendingList("restored-user").blockingGet().size());
            assertEquals(0, migrated.outboxDao().pendingList("another-user").blockingGet().size());
            assertTrue(migrated.outboxDao().byId("cm-legacy", "restored-user")
                    .blockingGet().isOwnerResolved());
        } finally {
            migrated.close();
        }
    }

    @Test
    public void v12LegacyRowsSealedWithoutStoredIdentityCannotBeClaimedByLaterLogin() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(EXPORTED_DB, 12);
        try {
            oldDb.execSQL(
                    "INSERT INTO outbox (clientMessageId, conversationId, type, content, "
                            + "replyToId, state, createdAt, attachmentsJson, mentionsJson, mentionAll) "
                            + "VALUES ('cm-unassigned', 'c-1', 'TEXT', 'queued', NULL, 'SENDING', "
                            + "100, '', '[]', 0)");
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase migratedSchema = helper.runMigrationsAndValidate(
                EXPORTED_DB, 13, true, AppDatabase.MIGRATION_12_13);
        migratedSchema.close();

        AppDatabase migrated = Room.databaseBuilder(context, AppDatabase.class, EXPORTED_DB)
                .allowMainThreadQueries()
                .build();
        try {
            migrated.outboxDao().sealUnassignedLegacyOwners().blockingAwait();
            migrated.outboxDao().assignLegacyOwner("later-user").blockingAwait();

            assertEquals(0, migrated.outboxDao().pendingList("later-user").blockingGet().size());
            assertEquals(1L, singleLong(migrated.getOpenHelper().getWritableDatabase(),
                    "SELECT COUNT(*) FROM outbox WHERE clientMessageId = 'cm-unassigned' "
                            + "AND ownerUserId = '' AND ownerResolved = 1"));
        } finally {
            migrated.close();
        }
    }

    @Test
    public void ownerScopedQueueSurvivesDatabaseReopenAndAccountCacheClear() {
        context.deleteDatabase(OUTBOX_DB);
        AppDatabase database = Room.databaseBuilder(context, AppDatabase.class, OUTBOX_DB)
                .allowMainThreadQueries()
                .build();
        try {
            database.messageDao().upsert(new MessageEntity(
                    "m-1", "c-1", 1L, "user-a", "A", "TEXT", "cached", null, null,
                    null, null, 100L, null, null, null)).blockingAwait();
            database.outboxDao().insert(new OutboxEntity(
                    "cm-a", "c-1", "TEXT", "queued", null, OutboxState.SENDING, 101L,
                    "", "[]", false, "user-a")).blockingAwait();
            database.close();

            database = Room.databaseBuilder(context, AppDatabase.class, OUTBOX_DB)
                    .allowMainThreadQueries()
                    .build();
            assertEquals("file-backed outbox survives a database reopen", 1,
                    database.outboxDao().pendingList("user-a").blockingGet().size());
            assertEquals("other account cannot see the durable row", 0,
                    database.outboxDao().pendingList("user-b").blockingGet().size());

            database.clearAccountDataPreservingOutbox();

            assertEquals(false, database.messageDao().exists("m-1").blockingGet());
            assertEquals(1, database.outboxDao().pendingList("user-a").blockingGet().size());
            assertEquals(0, database.outboxDao().pendingList("user-b").blockingGet().size());
        } finally {
            database.close();
        }
    }

    @Test
    public void v9SchemaCreatesFromExportedJson() throws Exception {
        helper.createDatabase(EXPORTED_DB, 9).close();
    }

    @Test
    public void v10SchemaCreatesFromExportedJson() throws Exception {
        helper.createDatabase(EXPORTED_DB, 10).close();
    }

    @Test
    public void v11SchemaCreatesFromExportedJson() throws Exception {
        helper.createDatabase(EXPORTED_DB, 11).close();
    }

    @Test
    public void migrateV1ToV7PreservesRowsAndBackfillsColumns() throws Exception {
        createLegacyDatabase(
                1,
                V1_SCHEMA,
                db -> {
                    db.execSQL(
                            "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                                    + "lastMessageContent, lastMessageSenderName, lastMessageAt, "
                                    + "unreadCount, otherUserName, otherUserAvatar) "
                                    + "VALUES ('c-1', 'GROUP', 'Team', 7, 'hi', 'Lan', 100, 3, NULL, NULL)");
                    db.execSQL(
                            "INSERT INTO messages (id, conversationId, seq, senderId, "
                                    + "senderDisplayName, type, content, clientMessageId, replyToId, "
                                    + "createdAt, attachmentsJson) "
                                    + "VALUES ('m-1', 'c-1', 7, 'u-1', 'Lan', 'TEXT', 'hi', NULL, NULL, 100, NULL)");
                    db.execSQL(
                            "INSERT INTO outbox (clientMessageId, conversationId, type, content, "
                                    + "replyToId, state, createdAt) "
                                    + "VALUES ('cm-1', 'c-1', 'TEXT', 'queued', NULL, 'SENDING', 100)");
                });

        try (SupportSQLiteDatabase db = openMigrated()) {
            assertV7ShapeAndPreservedRows(db);
        }
    }

    @Test
    public void migrateV6ToV7AddsEditDeleteAndInboxColumns() throws Exception {
        createLegacyDatabase(
                6,
                V6_SCHEMA,
                db -> {
                    db.execSQL(
                            "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                                    + "lastMessageContent, lastMessageSenderName, lastMessageAt, "
                                    + "unreadCount, otherUserName, otherUserAvatar, peerReadSeq, avatarUrl, myRole) "
                                    + "VALUES ('c-1', 'DIRECT', NULL, 7, 'hi', 'Lan', 100, 3, 'Lan', NULL, 4, NULL, NULL)");
                    db.execSQL(
                            "INSERT INTO messages (id, conversationId, seq, senderId, "
                                    + "senderDisplayName, type, content, clientMessageId, replyToId, "
                                    + "replyToSenderName, replyToSnippet, createdAt, attachmentsJson) "
                                    + "VALUES ('m-1', 'c-1', 7, 'u-1', 'Lan', 'TEXT', 'hi', NULL, NULL, "
                                    + "NULL, NULL, 100, NULL)");
                });

        try (SupportSQLiteDatabase db = openMigrated()) {
            try (Cursor cursor = db.query("SELECT editedAtEpochMs, deletedAtEpochMs FROM messages")) {
                assertTrue(cursor.moveToFirst());
                assertTrue(cursor.isNull(0));
                assertTrue(cursor.isNull(1));
            }
            try (Cursor cursor =
                    db.query("SELECT otherUserId, lastMessageDeleted, peerReadSeq FROM conversations")) {
                assertTrue(cursor.moveToFirst());
                assertTrue(cursor.isNull(0));
                assertEquals(0, cursor.getInt(1));
                assertEquals(4L, cursor.getLong(2));
            }
        }
    }

    @Test
    public void migrateV5ToV6AddsNullableRoleWithoutTouchingTheRest() throws Exception {
        createLegacyDatabase(
                5,
                V5_SCHEMA,
                db ->
                        db.execSQL(
                                "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                                        + "lastMessageContent, lastMessageSenderName, lastMessageAt, "
                                        + "unreadCount, otherUserName, otherUserAvatar, peerReadSeq, avatarUrl) "
                                        + "VALUES ('c-1', 'GROUP', 'Team', 7, 'hi', 'Lan', 100, 3, NULL, NULL, 4, 'avatar-1')"));

        try (SupportSQLiteDatabase db = openMigrated()) {
            try (Cursor cursor = db.query("SELECT myRole, peerReadSeq, avatarUrl FROM conversations")) {
                assertTrue(cursor.moveToFirst());
                assertTrue(cursor.isNull(0));
                assertEquals(4L, cursor.getLong(1));
                assertEquals("avatar-1", cursor.getString(2));
            }
        }
    }

    @Test
    public void migrateV7ToV8AddsUsersCallLogsOfflineOutboxAndPeerDeliveredSeq() throws Exception {
        createLegacyDatabase(
                7,
                V7_SCHEMA,
                db -> {
                    db.execSQL(
                            "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                                    + "lastMessageContent, lastMessageSenderName, lastMessageAt, "
                                    + "unreadCount, otherUserName, otherUserAvatar, peerReadSeq, "
                                    + "avatarUrl, myRole, otherUserId, lastMessageDeleted) "
                                    + "VALUES ('c-1', 'DIRECT', NULL, 7, 'hi', 'Lan', 100, 3, 'Lan', "
                                    + "NULL, 4, NULL, NULL, 'u-peer', 0)");
                    db.execSQL(
                            "INSERT INTO messages (id, conversationId, seq, senderId, "
                                    + "senderDisplayName, type, content, clientMessageId, replyToId, "
                                    + "replyToSenderName, replyToSnippet, createdAt, attachmentsJson, "
                                    + "editedAtEpochMs, deletedAtEpochMs) "
                                    + "VALUES ('m-1', 'c-1', 7, 'u-1', 'Lan', 'TEXT', 'hi', NULL, NULL, "
                                    + "NULL, NULL, 100, NULL, NULL, NULL)");
                });

        try (SupportSQLiteDatabase db = openMigrated()) {
            try (Cursor cursor = db.query("SELECT peerDeliveredSeq, peerReadSeq FROM conversations WHERE id = 'c-1'")) {
                assertTrue(cursor.moveToFirst());
                assertEquals(0L, cursor.getLong(0));
                assertEquals(4L, cursor.getLong(1));
            }

            db.execSQL(
                    "INSERT INTO users (id, username, displayName, phoneNumber, avatarUrl, bio, lastSeenAt, updatedAt) "
                            + "VALUES ('u-1', 'lan123', 'Lan Nguyen', '+84901234567', 'http://avatar.jpg', 'Hello', 1000, 2000)");
            try (Cursor cursor = db.query("SELECT displayName, phoneNumber FROM users WHERE id = 'u-1'")) {
                assertTrue(cursor.moveToFirst());
                assertEquals("Lan Nguyen", cursor.getString(0));
                assertEquals("+84901234567", cursor.getString(1));
            }

            db.execSQL(
                    "INSERT INTO call_logs (callId, conversationId, peerId, peerDisplayName, peerAvatarUrl, direction, callType, startedAt, durationSeconds, endReason) "
                            + "VALUES ('call-1', 'c-1', 'u-peer', 'Lan', NULL, 'INCOMING', 'AUDIO', 5000, 45, 'COMPLETED')");
            try (Cursor cursor = db.query("SELECT peerId, durationSeconds, endReason FROM call_logs WHERE callId = 'call-1'")) {
                assertTrue(cursor.moveToFirst());
                assertEquals("u-peer", cursor.getString(0));
                assertEquals(45L, cursor.getLong(1));
                assertEquals("COMPLETED", cursor.getString(2));
            }

            db.execSQL(
                    "INSERT INTO offline_outbox (id, idempotencyKey, actionType, payloadJson, state, retryCount, createdAtEpochMs, lastAttemptEpochMs) "
                            + "VALUES ('outbox-1', 'idemp-1', 'SEND_MESSAGE', '{\"text\":\"test\"}', 'SENDING', 0, 100, 0)");
            try (Cursor cursor = db.query("SELECT actionType, state FROM offline_outbox WHERE id = 'outbox-1'")) {
                assertTrue(cursor.moveToFirst());
                assertEquals("SEND_MESSAGE", cursor.getString(0));
                assertEquals("SENDING", cursor.getString(1));
            }
        }
    }

    private void assertV7ShapeAndPreservedRows(SupportSQLiteDatabase db) {
        try (Cursor cursor =
                db.query(
                        "SELECT title, unreadCount, peerReadSeq, avatarUrl, myRole, "
                                + "otherUserId, lastMessageDeleted FROM conversations")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Team", cursor.getString(0));
            assertEquals(3, cursor.getInt(1));
            assertEquals(0L, cursor.getLong(2));
            assertTrue(cursor.isNull(3));
            assertTrue(cursor.isNull(4));
            assertTrue(cursor.isNull(5));
            assertEquals(0, cursor.getInt(6));
        }
        try (Cursor cursor =
                db.query(
                        "SELECT id, content, replyToSenderName, replyToSnippet, "
                                + "editedAtEpochMs, deletedAtEpochMs FROM messages")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("m-1", cursor.getString(0));
            assertEquals("hi", cursor.getString(1));
            assertTrue(cursor.isNull(2));
            assertTrue(cursor.isNull(3));
            assertTrue(cursor.isNull(4));
            assertTrue(cursor.isNull(5));
        }
        try (Cursor cursor = db.query("SELECT content, attachmentsJson FROM outbox")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("queued", cursor.getString(0));
            assertEquals("", cursor.getString(1));
        }
        // The interaction tables exist with the shape every writer expects.
        db.execSQL(
                "INSERT INTO pinned_messages (conversationId, messageId, pinnedAt) "
                        + "VALUES ('c-1', 'm-1', 1)");
        db.execSQL(
                "INSERT INTO message_reactions (messageId, conversationId, emoji, count, reactedByMe) "
                        + "VALUES ('m-1', 'c-1', 'x', 1, 0)");
    }

    /** Opens the legacy file through Room with the production migrations. */
    private SupportSQLiteDatabase openMigrated() {
        return Room.databaseBuilder(context, AppDatabase.class, LEGACY_DB)
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
                        AppDatabase.MIGRATION_12_13)
                .allowMainThreadQueries()
                .build()
                .getOpenHelper()
                .getWritableDatabase();
    }

    private void createLegacyDatabase(int version, List<String> ddl, Consumer<SQLiteDatabase> seed) {
        context.deleteDatabase(LEGACY_DB);
        File file = context.getDatabasePath(LEGACY_DB);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            for (String statement : ddl) {
                db.execSQL(statement);
            }
            seed.accept(db);
            db.setVersion(version);
        } finally {
            db.close();
        }
    }

    private static long singleLong(SupportSQLiteDatabase db, String sql) {
        try (Cursor cursor = db.query(sql)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private static String singleString(SupportSQLiteDatabase db, String sql) {
        try (Cursor cursor = db.query(sql)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    private static List<String> extendV1Schema() {
        List<String> schema = new ArrayList<>(V1_SCHEMA);
        schema.addAll(
                Arrays.asList(
                        // v2: pending attachment ids must survive a process restart.
                        "ALTER TABLE outbox ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''",
                        "ALTER TABLE messages ADD COLUMN replyToSenderName TEXT",
                        "ALTER TABLE messages ADD COLUMN replyToSnippet TEXT",
                        "CREATE TABLE IF NOT EXISTS `message_reactions` ("
                                + "`messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, "
                                + "`emoji` TEXT NOT NULL, `count` INTEGER NOT NULL, "
                                + "`reactedByMe` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `emoji`))",
                        "CREATE INDEX IF NOT EXISTS `index_message_reactions_conversationId` "
                                + "ON `message_reactions` (`conversationId`)",
                        "CREATE TABLE IF NOT EXISTS `pinned_messages` ("
                                + "`conversationId` TEXT NOT NULL, `messageId` TEXT NOT NULL, "
                                + "`pinnedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`, `messageId`))",
                        "ALTER TABLE conversations ADD COLUMN peerReadSeq INTEGER NOT NULL DEFAULT 0",
                        "ALTER TABLE conversations ADD COLUMN avatarUrl TEXT"));
        return schema;
    }

    private static List<String> extendV5Schema() {
        List<String> schema = new ArrayList<>(V5_SCHEMA);
        schema.add("ALTER TABLE conversations ADD COLUMN myRole TEXT");
        return schema;
    }

    private static List<String> extendV6Schema() {
        List<String> schema = new ArrayList<>(V6_SCHEMA);
        schema.addAll(
                Arrays.asList(
                        "ALTER TABLE messages ADD COLUMN editedAtEpochMs INTEGER",
                        "ALTER TABLE messages ADD COLUMN deletedAtEpochMs INTEGER",
                        "ALTER TABLE conversations ADD COLUMN otherUserId TEXT",
                        "ALTER TABLE conversations ADD COLUMN lastMessageDeleted INTEGER NOT NULL DEFAULT 0"));
        return schema;
    }
}
