package com.nexachat.core.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.sqlite.db.SupportSQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RoomV8MigrationUnitTest {

    @Test
    public void schemaAdvancesForPersistentMessageMentions() {
        String schema;
        try {
            schema = readExportedSchema("9.json");
        } catch (IOException error) {
            throw new AssertionError("Version 9 Room schema must be exported", error);
        }
        assertTrue("message mentions require schema version 9",
                Pattern.compile("\\\"version\\\"\\s*:\\s*9").matcher(schema).find());
    }

    @Test
    public void migration8To9AddsDefaultedMentionColumns() throws IOException {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_8_9.migrate(db);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(4)).execSQL(sql.capture());
        assertEquals(java.util.List.of(
                "ALTER TABLE messages ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'",
                "ALTER TABLE messages ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE outbox ADD COLUMN mentionsJson TEXT NOT NULL DEFAULT '[]'",
                "ALTER TABLE outbox ADD COLUMN mentionAll INTEGER NOT NULL DEFAULT 0"), sql.getAllValues());

        String schema = readExportedSchema("9.json");
        assertMentionColumn(schema, "mentionsJson", "TEXT");
        assertMentionColumn(schema, "mentionAll", "INTEGER");
    }

    private static void assertMentionColumn(String schema, String name, String affinity) {
        assertTrue("Exported schema must declare " + name,
                Pattern.compile("\\\"columnName\\\"\\s*:\\s*\\\"" + name
                                + "\\\"[\\s\\S]*?\\\"affinity\\\"\\s*:\\s*\\\"" + affinity + "\\\"")
                        .matcher(schema).find());
    }

    /** DDL smoke only; real row-preservation behavior is covered by RoomMigrationInstrumentedTest. */
    @Test
    public void migration7To8DdlMatchesExportedSchema() throws IOException {
        SupportSQLiteDatabase db = mock(SupportSQLiteDatabase.class);
        AppDatabase.MIGRATION_7_8.migrate(db);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(db, org.mockito.Mockito.times(7)).execSQL(sqlCaptor.capture());

        java.util.List<String> executed = sqlCaptor.getAllValues();
        String schemaJson = readExportedSchema("8.json");

        assertTableDdlMatchesSchema(executed, schemaJson, "users");
        assertTableDdlMatchesSchema(executed, schemaJson, "call_logs");
        assertTableDdlMatchesSchema(executed, schemaJson, "offline_outbox");
        assertIndexDdlMatchesSchema(executed, schemaJson, "index_users_displayName", "users");
        assertIndexDdlMatchesSchema(executed, schemaJson, "index_call_logs_startedAt", "call_logs");
        assertIndexDdlMatchesSchema(
                executed, schemaJson, "index_offline_outbox_idempotencyKey", "offline_outbox");

        String addedPeerDeliveredSeq =
                findStatement(executed,
                        "ALTER TABLE `conversations` ADD COLUMN `peerDeliveredSeq`");
        assertEquals(
                "ALTER TABLE `conversations` ADD COLUMN `peerDeliveredSeq` INTEGER NOT NULL DEFAULT 0",
                normalizeSql(addedPeerDeliveredSeq));
        assertTrue(
                "Exported v8 schema must declare peerDeliveredSeq as a required integer",
                Pattern.compile(
                                "\\\"columnName\\\"\\s*:\\s*\\\"peerDeliveredSeq\\\""
                                        + "\\s*,\\s*\\\"affinity\\\"\\s*:\\s*\\\"INTEGER\\\""
                                        + "\\s*,\\s*\\\"notNull\\\"\\s*:\\s*true")
                        .matcher(schemaJson)
                        .find());
    }

    private static void assertTableDdlMatchesSchema(
            java.util.List<String> executed, String schemaJson, String tableName) {
        String expected = exportedTableCreateSql(schemaJson, tableName).replace("${TABLE_NAME}", tableName);
        String actual = findStatement(executed, "CREATE TABLE IF NOT EXISTS `" + tableName + "`");
        assertEquals("Migration DDL must match exported " + tableName + " columns and constraints",
                normalizeSql(expected), normalizeSql(actual));
    }

    private static void assertIndexDdlMatchesSchema(
            java.util.List<String> executed, String schemaJson, String indexName, String tableName) {
        String expected = exportedIndexCreateSql(schemaJson, indexName).replace("${TABLE_NAME}", tableName);
        String actual = executed.stream()
                .filter(statement -> statement.contains("`" + indexName + "`"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing migration index SQL: " + indexName));
        assertEquals("Migration index DDL must match exported schema index " + indexName,
                normalizeSql(expected), normalizeSql(actual));
    }

    private static String findStatement(java.util.List<String> statements, String prefix) {
        return statements.stream()
                .filter(statement -> normalizeSql(statement).startsWith(normalizeSql(prefix)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing migration SQL: " + prefix));
    }

    private static String exportedTableCreateSql(String schemaJson, String tableName) {
        Pattern pattern = Pattern.compile(
                "\\\"tableName\\\"\\s*:\\s*\\\"" + Pattern.quote(tableName)
                        + "\\\"\\s*,\\s*\\\"createSql\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = pattern.matcher(schemaJson);
        if (!matcher.find()) {
            throw new AssertionError("Exported schema is missing table " + tableName);
        }
        return matcher.group(1);
    }

    private static String exportedIndexCreateSql(String schemaJson, String indexName) {
        Pattern pattern = Pattern.compile(
                "\\\"name\\\"\\s*:\\s*\\\"" + Pattern.quote(indexName)
                        + "\\\"[\\s\\S]*?\\\"createSql\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = pattern.matcher(schemaJson);
        if (!matcher.find()) {
            throw new AssertionError("Exported schema is missing index " + indexName);
        }
        return matcher.group(1);
    }

    private static String readExportedSchema(String filename) throws IOException {
        String resource =
                "schemas/com.nexachat.core.database.AppDatabase/" + filename;
        try (java.io.InputStream input =
                RoomV8MigrationUnitTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        Path modulePath = Path.of(
                System.getProperty("user.dir"), "schemas", "com.nexachat.core.database.AppDatabase", filename);
        Path repoRootPath = Path.of(
                System.getProperty("user.dir"), "android", "app", "schemas",
                "com.nexachat.core.database.AppDatabase", filename);
        Path schemaPath = Files.isRegularFile(modulePath) ? modulePath : repoRootPath;
        assertTrue("Exported Room schema not found at " + schemaPath, Files.isRegularFile(schemaPath));
        return new String(Files.readAllBytes(schemaPath), StandardCharsets.UTF_8);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    @Test
    public void userEntityPropertiesAndContract() {
        UserEntity user1 = new UserEntity(
                "user-1", "alice", "Alice Wonder", "+84901234567",
                "http://avatar.jpg", "Bio text", 1000L, 2000L);
        UserEntity user2 = new UserEntity(
                "user-1", "alice", "Alice Wonder", "+84901234567",
                "http://avatar.jpg", "Bio text", 1000L, 2000L);
        UserEntity userDiff = new UserEntity(
                "user-2", "bob", "Bob Dylan", null,
                null, null, null, null);

        assertEquals("user-1", user1.getId());
        assertEquals("alice", user1.getUsername());
        assertEquals("Alice Wonder", user1.getDisplayName());
        assertEquals("+84901234567", user1.getPhoneNumber());
        assertEquals("http://avatar.jpg", user1.getAvatarUrl());
        assertEquals("Bio text", user1.getBio());
        assertEquals(Long.valueOf(1000L), user1.getLastSeenAt());
        assertEquals(Long.valueOf(2000L), user1.getUpdatedAt());

        assertEquals(user1, user2);
        assertEquals(user1.hashCode(), user2.hashCode());
        assertNotEquals(user1, userDiff);
        assertNotNull(user1.toString());
    }

    @Test
    public void callLogEntityPropertiesAndContract() {
        CallLogEntity log1 = new CallLogEntity(
                "call-100", "conv-200", "peer-300", "Alice",
                "http://avatar.jpg", "INCOMING", "AUDIO", 5000L, 120L, "COMPLETED");
        CallLogEntity log2 = new CallLogEntity(
                "call-100", "conv-200", "peer-300", "Alice",
                "http://avatar.jpg", "INCOMING", "AUDIO", 5000L, 120L, "COMPLETED");
        CallLogEntity logDiff = new CallLogEntity(
                "call-101", "conv-200", "peer-300", "Alice",
                null, "OUTGOING", "VIDEO", 6000L, 0L, "MISSED");

        assertEquals("call-100", log1.getCallId());
        assertEquals("conv-200", log1.getConversationId());
        assertEquals("peer-300", log1.getPeerId());
        assertEquals("Alice", log1.getPeerDisplayName());
        assertEquals("http://avatar.jpg", log1.getPeerAvatarUrl());
        assertEquals("INCOMING", log1.getDirection());
        assertEquals("AUDIO", log1.getCallType());
        assertEquals(5000L, log1.getStartedAt());
        assertEquals(120L, log1.getDurationSeconds());
        assertEquals("COMPLETED", log1.getEndReason());

        assertEquals(log1, log2);
        assertEquals(log1.hashCode(), log2.hashCode());
        assertNotEquals(log1, logDiff);
        assertNotNull(log1.toString());
    }

    @Test
    public void offlineOutboxEntityPropertiesAndTransitions() {
        OfflineOutboxEntity item = new OfflineOutboxEntity(
                "item-1", "idemp-key-1", "SEND_MESSAGE", "{\"text\":\"hi\"}",
                OutboxState.PENDING, 0, 1000L, 0L);

        assertEquals("item-1", item.getId());
        assertEquals("idemp-key-1", item.getIdempotencyKey());
        assertEquals("SEND_MESSAGE", item.getActionType());
        assertEquals("{\"text\":\"hi\"}", item.getPayloadJson());
        assertEquals(OutboxState.PENDING, item.getState());
        assertEquals(0, item.getRetryCount());
        assertEquals(1000L, item.getCreatedAtEpochMs());
        assertEquals(0L, item.getLastAttemptEpochMs());

        OfflineOutboxEntity inProgress = item.withStateAndAttempt(OutboxState.IN_PROGRESS, 1, 1050L);
        assertEquals(OutboxState.IN_PROGRESS, inProgress.getState());
        assertEquals(1, inProgress.getRetryCount());
        assertEquals(1050L, inProgress.getLastAttemptEpochMs());

        assertNotNull(item.toString());
    }

    @Test
    public void conversationEntityPreservesPeerDeliveredSeq() {
        ConversationEntity conv = new ConversationEntity(
                "c-1", "DIRECT", "Chat", 10L, "Hello", "Lan",
                1000L, 0, "Lan", null, null, 8L, null, "u-peer", false, 9L);

        assertEquals(8L, conv.getPeerReadSeq());
        assertEquals(9L, conv.getPeerDeliveredSeq());

        ConversationEntity updated = conv.withPeerDeliveredSeq(10L);
        assertEquals(10L, updated.getPeerDeliveredSeq());
        assertEquals(8L, updated.getPeerReadSeq());
    }
}
