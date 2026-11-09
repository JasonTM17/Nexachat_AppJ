package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Exercises Room migrations against the exported schemas and a real SQLite database.
 *
 * <p>The oldest exported schema in this repository is version 6. Earlier legacy fixtures remain
 * covered by {@link RoomMigrationTest}; this class validates both exported transitions and the
 * complete chain from the oldest exported schema to the current database version.
 */
@RunWith(AndroidJUnit4.class)
public final class RoomMigrationInstrumentedTest {
    private static final String V6_TO_V7_DB = "nexachat-room-v6-v7.db";
    private static final String V7_TO_V8_DB = "nexachat-room-v7-v8.db";
    private static final String V8_TO_V9_DB = "nexachat-room-v8-v9.db";
    private static final String V9_TO_V10_DB = "nexachat-room-v9-v10.db";
    private static final String V10_TO_V11_DB = "nexachat-room-v10-v11.db";
    private static final String V11_TO_V12_DB = "nexachat-room-v11-v12.db";
    private static final String FULL_CHAIN_DB = "nexachat-room-v6-v12.db";
    private static final String ATTACHMENTS_JSON =
            "[{\"fileId\":\"attachment-1\",\"url\":\"https://example.test/image.jpg\","
                    + "\"mimeType\":\"image/jpeg\"}]";
    private static final long CREATED_AT = 1_700_000_000_000L;
    private static final long EDITED_AT = 1_700_000_000_123L;

    @Rule
    public final MigrationTestHelper helper =
            new MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(), AppDatabase.class);

    private final Context context = ApplicationProvider.getApplicationContext();

    @After
    public void cleanUp() {
        context.deleteDatabase(V6_TO_V7_DB);
        context.deleteDatabase(V7_TO_V8_DB);
        context.deleteDatabase(V8_TO_V9_DB);
        context.deleteDatabase(V9_TO_V10_DB);
        context.deleteDatabase(V10_TO_V11_DB);
        context.deleteDatabase(V11_TO_V12_DB);
        context.deleteDatabase(FULL_CHAIN_DB);
    }

    @Test
    public void migrateV6ToV7PreservesRowsAndAddsExpectedDefaults() throws Exception {
        context.deleteDatabase(V6_TO_V7_DB);
        SupportSQLiteDatabase oldDb = helper.createDatabase(V6_TO_V7_DB, 6);
        try {
            seedRows(oldDb, 6);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V6_TO_V7_DB, 7, true, AppDatabase.MIGRATION_6_7);
        try {
            assertExistingRows(db);
            assertV7DefaultsFromV6(db);
            assertV7Indexes(db);
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void migrateV7ToV8PreservesRowsAndAddsExpectedDefaults() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(V7_TO_V8_DB, 7);
        try {
            seedRows(oldDb, 7);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V7_TO_V8_DB, 8, true, AppDatabase.MIGRATION_7_8);
        try {
            assertExistingRows(db);
            assertV7ValuesSurviveV8(db);
            assertEquals("peerDeliveredSeq defaults to zero", 0L,
                    singleLong(db, "SELECT peerDeliveredSeq FROM conversations WHERE id = 'c-1'"));
            assertV8Indexes(db);
            assertNewTablesAcceptRows(db);
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void migrateV8ToV9PreservesRowsAndAddsSafePreferenceDefaults() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(V8_TO_V9_DB, 8);
        try {
            seedRows(oldDb, 8);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V8_TO_V9_DB, 9, true, AppDatabase.MIGRATION_8_9);
        try {
            assertExistingRows(db);
            assertV9Defaults(db);
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void migrateV9ToV10PreservesRowsAndAddsExpectedDefaults() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(V9_TO_V10_DB, 9);
        try {
            seedRows(oldDb, 9);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V9_TO_V10_DB, 10, true, AppDatabase.MIGRATION_9_10);
        try {
            assertExistingRows(db);
            assertV9Defaults(db);
            assertEquals("pinned defaults to zero", 0L,
                    singleLong(db, "SELECT pinned FROM conversations WHERE id = 'c-1'"));
            assertNull("pinnedAt defaults to null",
                    singleNullableLong(db, "SELECT pinnedAt FROM conversations WHERE id = 'c-1'"));
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void migrateV10ToV11PreservesRowsAndAddsMentionDefaults() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(V10_TO_V11_DB, 10);
        try {
            seedRows(oldDb, 10);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V10_TO_V11_DB, 11, true, AppDatabase.MIGRATION_10_11);
        try {
            assertExistingRows(db);
            assertMentionDefaults(db);
            assertV9Defaults(db);
            assertEquals("pinned defaults to zero", 0L,
                    singleLong(db, "SELECT pinned FROM conversations WHERE id = 'c-1'"));
            assertNull("pinnedAt defaults to null",
                    singleNullableLong(db, "SELECT pinnedAt FROM conversations WHERE id = 'c-1'"));
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void migrateV11ToV12PreservesPopulatedRowsAndAddsLabelCache() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(V11_TO_V12_DB, 11);
        try {
            seedRows(oldDb, 11);
        } finally {
            oldDb.close();
        }

        SupportSQLiteDatabase db = helper.runMigrationsAndValidate(
                V11_TO_V12_DB, 12, true, AppDatabase.MIGRATION_11_12);
        try {
            assertExistingRows(db);
            assertV11DataPreserved(db);
            assertEquals("new label cache defaults to an empty assignment", "",
                    singleString(db,
                            "SELECT labelIdsCsv FROM conversations WHERE id = 'c-1'"));
            assertNoForeignKeyViolations(db);
        } finally {
            db.close();
        }
    }

    @Test
    public void fullChainFromOldestExportedSchemaPreservesRows() throws Exception {
        SupportSQLiteDatabase oldDb = helper.createDatabase(FULL_CHAIN_DB, 6);
        try {
            seedRows(oldDb, 6);
        } finally {
            oldDb.close();
        }

        AppDatabase database = Room.databaseBuilder(context, AppDatabase.class, FULL_CHAIN_DB)
                .addMigrations(allMigrations())
                .allowMainThreadQueries()
                .build();
        try {
            SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
            assertEquals("full chain reaches the current schema", 12, db.getVersion());
            assertExistingRows(db);
            assertV7DefaultsFromV6(db);
            assertEquals("peerDeliveredSeq defaults to zero", 0L,
                    singleLong(db, "SELECT peerDeliveredSeq FROM conversations WHERE id = 'c-1'"));
            assertEquals("pinned defaults to zero", 0L,
                    singleLong(db, "SELECT pinned FROM conversations WHERE id = 'c-1'"));
            assertNull("pinnedAt defaults to null",
                    singleNullableLong(db, "SELECT pinnedAt FROM conversations WHERE id = 'c-1'"));
            assertV8Indexes(db);
            assertNewTablesAcceptRows(db);
            assertV9Defaults(db);
            assertMentionDefaults(db);
            assertEquals("full-chain label cache defaults empty", "",
                    singleString(db,
                            "SELECT labelIdsCsv FROM conversations WHERE id = 'c-1'"));
            assertNoForeignKeyViolations(db);
        } finally {
            database.close();
        }
    }

    private static Migration[] allMigrations() {
        return new Migration[] {
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
            AppDatabase.MIGRATION_11_12
        };
    }

    private static void assertMentionDefaults(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "SELECT mentionsJson, mentionAll FROM messages WHERE id = 'm-1'")) {
            assertTrue("message row remains after mention migration", cursor.moveToFirst());
            assertEquals("[]", cursor.getString(0));
            assertEquals(0, cursor.getInt(1));
        }
        try (Cursor cursor = db.query(
                "SELECT mentionsJson, mentionAll FROM outbox WHERE clientMessageId = 'cm-1'")) {
            assertTrue("outbox row remains after mention migration", cursor.moveToFirst());
            assertEquals("[]", cursor.getString(0));
            assertEquals(0, cursor.getInt(1));
        }
    }

    private static void assertV11DataPreserved(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "SELECT muted, mutedUntilEpochMs, archived, aiOptOut, pinned, pinnedAt "
                        + "FROM conversations WHERE id = 'c-1'")) {
            assertTrue("conversation row remains after labels migration", cursor.moveToFirst());
            assertEquals(1, cursor.getInt(0));
            assertEquals(CREATED_AT + 3L, cursor.getLong(1));
            assertEquals(1, cursor.getInt(2));
            assertEquals(1, cursor.getInt(3));
            assertEquals(1, cursor.getInt(4));
            assertEquals(CREATED_AT + 2L, cursor.getLong(5));
        }
        try (Cursor cursor = db.query(
                "SELECT mentionsJson, mentionAll FROM messages WHERE id = 'm-1'")) {
            assertTrue("message row remains after labels migration", cursor.moveToFirst());
            assertEquals("[{\"userId\":\"u-2\",\"offset\":0,\"length\":4}]",
                    cursor.getString(0));
            assertEquals(1, cursor.getInt(1));
        }
        try (Cursor cursor = db.query(
                "SELECT mentionsJson, mentionAll FROM outbox WHERE clientMessageId = 'cm-1'")) {
            assertTrue("outbox row remains after labels migration", cursor.moveToFirst());
            assertEquals("[{\"userId\":\"u-2\",\"offset\":0,\"length\":4}]",
                    cursor.getString(0));
            assertEquals(1, cursor.getInt(1));
        }
    }

    private static void seedRows(SupportSQLiteDatabase db, int version) {
        if (version == 6) {
            db.execSQL(
                    "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                            + "lastMessageContent, lastMessageSenderName, lastMessageAt, unreadCount, "
                            + "otherUserName, otherUserAvatar, avatarUrl, peerReadSeq, myRole) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        "c-1", "DIRECT", null, 23L, "Pinned preview", "Lan", CREATED_AT,
                        4, "Lan", "avatar.png", "group-avatar.png", 12L, "MEMBER"
                    });
            db.execSQL(
                    "INSERT INTO messages (id, conversationId, seq, senderId, senderDisplayName, "
                            + "type, content, clientMessageId, replyToId, replyToSenderName, "
                            + "replyToSnippet, createdAt, attachmentsJson) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {
                        "m-1", "c-1", 23L, "u-1", "Lan", "IMAGE", "Photo", null, null,
                        null, null, CREATED_AT, ATTACHMENTS_JSON
                    });
        } else {
            String conversationColumns =
                    "INSERT INTO conversations (id, type, title, lastMessageSeq, "
                            + "lastMessageContent, lastMessageSenderName, lastMessageAt, unreadCount, "
                            + "otherUserName, otherUserAvatar, avatarUrl, peerReadSeq, myRole, "
                            + "otherUserId, lastMessageDeleted";
            String conversationValues = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
            // Arrays.asList accepts the null title and preview that List.of rejects.
            java.util.List<Object> argsList = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "c-1", "DIRECT", null, 23L, null, "Lan", CREATED_AT, 4, "Lan",
                    "avatar.png", "group-avatar.png", 12L, "MEMBER", "peer-2", 1
            ));
            if (version >= 8) {
                conversationColumns += ", peerDeliveredSeq";
                conversationValues += ", ?";
                argsList.add(2L);
            }
            if (version >= 9) {
                conversationColumns += ", muted, mutedUntilEpochMs, archived, aiOptOut";
                conversationValues += ", ?, ?, ?, ?";
                argsList.add(0);
                argsList.add(null);
                argsList.add(0);
                argsList.add(0);
            }
            if (version >= 10) {
                conversationColumns += ", pinned, pinnedAt";
                conversationValues += ", ?, ?";
                argsList.add(version >= 11 ? 1 : 0);
                argsList.add(version >= 11 ? CREATED_AT + 2L : null);
            }
            if (version >= 11) {
                argsList.set(argsList.size() - 6, 1);
                argsList.set(argsList.size() - 5, CREATED_AT + 3L);
                argsList.set(argsList.size() - 4, 1);
                argsList.set(argsList.size() - 3, 1);
            }
            db.execSQL(conversationColumns + ") " + conversationValues + ")", argsList.toArray());
            String messageColumns = "INSERT INTO messages (id, conversationId, seq, senderId, "
                    + "senderDisplayName, type, content, clientMessageId, replyToId, "
                    + "replyToSenderName, replyToSnippet, createdAt, attachmentsJson, "
                    + "editedAtEpochMs, deletedAtEpochMs";
            String messageValues = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
            java.util.List<Object> messageArgs = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "m-1", "c-1", 23L, "u-1", "Lan", "IMAGE", "Photo", null, null,
                    null, null, CREATED_AT, ATTACHMENTS_JSON, EDITED_AT, null));
            if (version >= 11) {
                messageColumns += ", mentionsJson, mentionAll";
                messageValues += ", ?, ?";
                messageArgs.add("[{\"userId\":\"u-2\",\"offset\":0,\"length\":4}]");
                messageArgs.add(1);
            }
            db.execSQL(messageColumns + ") " + messageValues + ")", messageArgs.toArray());
        }

        String outboxColumns = "INSERT INTO outbox (clientMessageId, conversationId, type, "
                + "content, replyToId, state, createdAt, attachmentsJson";
        String outboxValues = "VALUES (?, ?, ?, ?, ?, ?, ?, ?";
        java.util.List<Object> outboxArgs = new java.util.ArrayList<>(java.util.Arrays.asList(
                "cm-1", "c-1", "IMAGE", "Queued photo", null, "SENDING", CREATED_AT,
                ATTACHMENTS_JSON));
        if (version >= 11) {
            outboxColumns += ", mentionsJson, mentionAll";
            outboxValues += ", ?, ?";
            outboxArgs.add("[{\"userId\":\"u-2\",\"offset\":0,\"length\":4}]");
            outboxArgs.add(1);
        }
        db.execSQL(outboxColumns + ") " + outboxValues + ")", outboxArgs.toArray());
        db.execSQL(
                "INSERT INTO message_reactions (messageId, conversationId, emoji, count, reactedByMe) "
                        + "VALUES (?, ?, ?, ?, ?)",
                new Object[] {"m-1", "c-1", "👍", 2, 1});
        db.execSQL(
                "INSERT INTO pinned_messages (conversationId, messageId, pinnedAt) VALUES (?, ?, ?)",
                new Object[] {"c-1", "m-1", CREATED_AT + 1L});
    }

    private static void assertExistingRows(SupportSQLiteDatabase db) {
        assertEquals("one conversation survives", 1L, count(db, "conversations"));
        assertEquals("one message survives", 1L, count(db, "messages"));
        assertEquals("one pending outbox row survives", 1L, count(db, "outbox"));
        assertEquals("one reaction survives", 1L, count(db, "message_reactions"));
        assertEquals("one pin survives", 1L, count(db, "pinned_messages"));

        try (Cursor cursor = db.query(
                "SELECT id, title, unreadCount, peerReadSeq, avatarUrl, myRole "
                        + "FROM conversations WHERE id = 'c-1'")) {
            assertTrue("conversation row is present", cursor.moveToFirst());
            assertEquals("c-1", cursor.getString(0));
            assertNull("title remains null", cursor.getString(1));
            assertEquals("unread badge survives", 4, cursor.getInt(2));
            assertEquals("peer read watermark survives", 12L, cursor.getLong(3));
            assertEquals("group avatar survives", "group-avatar.png", cursor.getString(4));
            assertEquals("member role survives", "MEMBER", cursor.getString(5));
        }
        try (Cursor cursor = db.query(
                "SELECT id, content, attachmentsJson FROM messages WHERE id = 'm-1'")) {
            assertTrue("message row is present", cursor.moveToFirst());
            assertEquals("m-1", cursor.getString(0));
            assertEquals("Photo", cursor.getString(1));
            assertEquals("message attachment JSON survives", ATTACHMENTS_JSON, cursor.getString(2));
        }
        try (Cursor cursor = db.query(
                "SELECT content, attachmentsJson FROM outbox WHERE clientMessageId = 'cm-1'")) {
            assertTrue("pending outbox row is present", cursor.moveToFirst());
            assertEquals("Queued photo", cursor.getString(0));
            assertEquals("outbox attachment JSON survives", ATTACHMENTS_JSON, cursor.getString(1));
        }
        try (Cursor cursor = db.query(
                "SELECT emoji, count, reactedByMe FROM message_reactions WHERE messageId = 'm-1'")) {
            assertTrue("reaction row is present", cursor.moveToFirst());
            assertEquals("reaction emoji survives", "👍", cursor.getString(0));
            assertEquals("reaction count survives", 2, cursor.getInt(1));
            assertEquals("reacted-by-me state survives", 1, cursor.getInt(2));
        }
        assertEquals("pin timestamp survives", CREATED_AT + 1L,
                singleLong(db, "SELECT pinnedAt FROM pinned_messages WHERE messageId = 'm-1'"));
    }

    private static void assertV7DefaultsFromV6(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "SELECT otherUserId, lastMessageDeleted FROM conversations WHERE id = 'c-1'")) {
            assertTrue("conversation remains after v6 to v7", cursor.moveToFirst());
            assertNull("new peer id is nullable", cursor.getString(0));
            assertEquals("new deleted flag defaults false", 0, cursor.getInt(1));
        }
        try (Cursor cursor = db.query(
                "SELECT editedAtEpochMs, deletedAtEpochMs FROM messages WHERE id = 'm-1'")) {
            assertTrue("message remains after v6 to v7", cursor.moveToFirst());
            assertTrue("edit time defaults null", cursor.isNull(0));
            assertTrue("delete time defaults null", cursor.isNull(1));
        }
    }

    private static void assertV7ValuesSurviveV8(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "SELECT otherUserId, lastMessageDeleted FROM conversations WHERE id = 'c-1'")) {
            assertTrue("conversation row remains after v7 to v8", cursor.moveToFirst());
            assertEquals("peer id survives", "peer-2", cursor.getString(0));
            assertEquals("deleted flag survives", 1, cursor.getInt(1));
        }
        assertEquals("edit timestamp survives v7 to v8", EDITED_AT,
                singleLong(db, "SELECT editedAtEpochMs FROM messages WHERE id = 'm-1'"));
    }

    private static void assertV7Indexes(SupportSQLiteDatabase db) {
        assertIndexExists(db, "index_messages_conversationId_seq", "messages");
        assertIndexExists(db, "index_message_reactions_conversationId", "message_reactions");
    }

    private static void assertV8Indexes(SupportSQLiteDatabase db) {
        assertV7Indexes(db);
        assertIndexExists(db, "index_users_displayName", "users");
        assertIndexExists(db, "index_call_logs_startedAt", "call_logs");
        String sql = indexSql(db, "index_offline_outbox_idempotencyKey", "offline_outbox");
        assertTrue("offline outbox idempotency index must remain unique",
                sql.toUpperCase(java.util.Locale.ROOT).contains("CREATE UNIQUE INDEX"));
    }

    private static void assertV9Defaults(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query(
                "SELECT muted, mutedUntilEpochMs, archived, aiOptOut "
                        + "FROM conversations WHERE id = 'c-1'")) {
            assertTrue("conversation row remains after v8 to v9", cursor.moveToFirst());
            assertEquals("mute defaults off", 0, cursor.getInt(0));
            assertTrue("mute expiry defaults null", cursor.isNull(1));
            assertEquals("archive defaults off", 0, cursor.getInt(2));
            assertEquals("AI opt-out defaults off", 0, cursor.getInt(3));
        }
    }

    private static void assertNewTablesAcceptRows(SupportSQLiteDatabase db) {
        db.execSQL(
                "INSERT INTO users (id, username, displayName, phoneNumber, avatarUrl, bio, "
                        + "lastSeenAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {"u-2", "migration-user", "Migration User", null, null, "fixture", 100L, 200L});
        db.execSQL(
                "INSERT INTO call_logs (callId, conversationId, peerId, peerDisplayName, "
                        + "peerAvatarUrl, direction, callType, startedAt, durationSeconds, endReason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {"call-1", "c-1", "peer-2", "Migration User", null,
                    "INCOMING", "AUDIO", 300L, 42L, "COMPLETED"});
        db.execSQL(
                "INSERT INTO offline_outbox (id, idempotencyKey, actionType, payloadJson, state, "
                        + "retryCount, createdAtEpochMs, lastAttemptEpochMs) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {"action-1", "idempotency-1", "SEND_MESSAGE", "{\"text\":\"fixture\"}",
                    "PENDING", 0, 400L, 0L});

        assertEquals("new user table accepts a row", 1L, count(db, "users"));
        assertEquals("new call log table accepts a row", 1L, count(db, "call_logs"));
        assertEquals("new offline outbox accepts a row", 1L, count(db, "offline_outbox"));
        assertEquals("user row is readable", "Migration User",
                singleString(db, "SELECT displayName FROM users WHERE id = 'u-2'"));
        assertEquals("call log row is readable", 42L,
                singleLong(db, "SELECT durationSeconds FROM call_logs WHERE callId = 'call-1'"));
        assertEquals("offline action payload is readable", "{\"text\":\"fixture\"}",
                singleString(db, "SELECT payloadJson FROM offline_outbox WHERE id = 'action-1'"));

        boolean duplicateKeyRejected = false;
        try {
            db.execSQL(
                    "INSERT INTO offline_outbox (id, idempotencyKey, actionType, payloadJson, "
                            + "state, retryCount, createdAtEpochMs, lastAttemptEpochMs) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[] {"action-2", "idempotency-1", "SEND_MESSAGE", "{}", "PENDING", 0, 500L, 0L});
        } catch (SQLiteConstraintException expected) {
            duplicateKeyRejected = true;
        }
        assertTrue("offline outbox rejects a duplicate idempotency key", duplicateKeyRejected);
    }

    private static void assertNoForeignKeyViolations(SupportSQLiteDatabase db) {
        try (Cursor cursor = db.query("PRAGMA foreign_key_check")) {
            assertFalse("migration database must have no foreign key violations", cursor.moveToFirst());
        }
    }

    private static void assertIndexExists(
            SupportSQLiteDatabase db, String indexName, String tableName) {
        assertTrue("missing index " + indexName,
                indexSql(db, indexName, tableName) != null);
    }

    private static String indexSql(
            SupportSQLiteDatabase db, String indexName, String tableName) {
        try (Cursor cursor = db.query(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '"
                        + indexName + "' AND tbl_name = '" + tableName + "'")) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static long count(SupportSQLiteDatabase db, String tableName) {
        return singleLong(db, "SELECT COUNT(*) FROM " + tableName);
    }

    private static long singleLong(SupportSQLiteDatabase db, String query) {
        try (Cursor cursor = db.query(query)) {
            assertTrue("query returned no row: " + query, cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    @androidx.annotation.Nullable
    private static Long singleNullableLong(SupportSQLiteDatabase db, String query) {
        try (Cursor cursor = db.query(query)) {
            assertTrue("query returned no row: " + query, cursor.moveToFirst());
            return cursor.isNull(0) ? null : cursor.getLong(0);
        }
    }

    private static String singleString(SupportSQLiteDatabase db, String query) {
        try (Cursor cursor = db.query(query)) {
            assertTrue("query returned no row: " + query, cursor.moveToFirst());
            return cursor.getString(0);
        }
    }
}
