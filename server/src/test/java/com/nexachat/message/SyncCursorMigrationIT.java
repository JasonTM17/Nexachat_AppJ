package com.nexachat.message;

import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncCursorMigrationIT {

    @Test
    void emptyDatabaseMigratesToV32AndValidates() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("sync-cursor-empty")) {
            Flyway flyway = flyway(database);
            flyway.migrate();
            flyway.validate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM flyway_schema_history WHERE version = '32' AND success",
                    String.class)).isEqualTo("32");
            assertThat(columnExists(jdbc, "conversations", "change_version")).isTrue();
            assertThat(columnExists(jdbc, "conversations", "sync_generation")).isTrue();
            assertThat(columnExists(jdbc, "messages", "change_version")).isTrue();
            assertThat(indexExists(jdbc, "idx_messages_conv_change_version")).isTrue();
        }
    }

    @Test
    void populatedV31BackfillIsDeterministicAndIncludesTombstonesAndEmptyConversations() {
        try (PostgresTestDatabase first = PostgresTestDatabase.create("sync-cursor-populated-a");
             PostgresTestDatabase second = PostgresTestDatabase.create("sync-cursor-populated-b")) {
            Map<UUID, Long> firstVersions = migratePopulatedV31(first);
            Map<UUID, Long> secondVersions = migratePopulatedV31(second);
            assertThat(firstVersions).isEqualTo(secondVersions);
        }
    }

    @Test
    void failedBackfillRollsBackTheEntireV32Migration() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("sync-cursor-failure")) {
            Flyway flyway = flyway(database, "31");
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            Fixture fixture = seedV31(jdbc);
            jdbc.execute("""
                    CREATE FUNCTION fail_sync_cursor_backfill() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'injected V32 backfill failure';
                    END;
                    $$
                    """);
            jdbc.execute("""
                    CREATE TRIGGER fail_sync_cursor_backfill
                    BEFORE UPDATE ON messages
                    FOR EACH ROW EXECUTE FUNCTION fail_sync_cursor_backfill()
                    """);

            assertThatThrownBy(() -> flyway(database).migrate()).isInstanceOf(RuntimeException.class);

            assertThat(columnExists(jdbc, "conversations", "change_version")).isFalse();
            assertThat(columnExists(jdbc, "messages", "change_version")).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '32' AND success",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT content FROM messages WHERE id = ?", String.class, fixture.firstMessageId()))
                    .isEqualTo("kept-after-backfill");
        }
    }

    private static Map<UUID, Long> migratePopulatedV31(PostgresTestDatabase database) {
        Flyway flyway = flyway(database, "31");
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        Fixture fixture = seedV31(jdbc);
        long started = System.nanoTime();
        flyway(database).migrate();
        long v32ElapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        flyway(database).validate();
        String postgresVersion = jdbc.queryForObject("SHOW server_version", String.class);
        int backfilledMessages = jdbc.queryForObject("""
                SELECT count(*) FROM messages
                WHERE conversation_id IN (?, ?)
                """, Integer.class, fixture.populatedConversationId(), fixture.secondConversationId());
        System.out.printf("SYNC_CURSOR_V32_MIGRATION pg=%s messages=%d elapsed_ms=%d scope=local-fixture%n",
                postgresVersion, backfilledMessages, v32ElapsedMillis);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, conversation_id, seq, content, edited_at, deleted_at, change_version
                FROM messages
                WHERE conversation_id IN (?, ?)
                ORDER BY conversation_id, seq, id
                """, fixture.populatedConversationId(), fixture.secondConversationId());
        Map<UUID, Long> versions = new java.util.LinkedHashMap<>();
        UUID previousConversation = null;
        long expectedVersion = 0;
        for (Map<String, Object> row : rows) {
            UUID conversationId = (UUID) row.get("conversation_id");
            if (!conversationId.equals(previousConversation)) {
                previousConversation = conversationId;
                expectedVersion = 0;
            }
            expectedVersion++;
            UUID messageId = (UUID) row.get("id");
            long version = ((Number) row.get("change_version")).longValue();
            versions.put(messageId, version);
            assertThat(version).isEqualTo(expectedVersion);
        }

        assertThat(rows).hasSize(3);
        assertThat(versions.get(fixture.firstMessageId())).isEqualTo(1L);
        assertThat(versions.get(fixture.tombstoneMessageId())).isEqualTo(2L);
        assertThat(versions.get(fixture.secondConversationMessageId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?", Long.class,
                fixture.populatedConversationId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?", Long.class,
                fixture.secondConversationId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT change_version = 0 AND sync_generation = 1 FROM conversations WHERE id = ?",
                Boolean.class, fixture.emptyConversationId())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT seq FROM messages WHERE id = ?", Long.class, fixture.firstMessageId()))
                .isEqualTo(fixture.firstSeq());
        assertThat(jdbc.queryForObject(
                "SELECT content FROM messages WHERE id = ?", String.class, fixture.firstMessageId()))
                .isEqualTo("kept-after-backfill");
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM messages WHERE id = ?", Boolean.class,
                fixture.tombstoneMessageId())).isTrue();
        assertThat(indexExists(jdbc, "idx_messages_conv_change_version")).isTrue();
        return versions;
    }

    private static Fixture seedV31(JdbcTemplate jdbc) {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID firstConversationId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID secondConversationId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID emptyConversationId = UUID.fromString("00000000-0000-0000-0000-000000000013");
        UUID firstMessageId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID tombstoneMessageId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        UUID secondConversationMessageId = UUID.fromString("00000000-0000-0000-0000-000000000023");
        jdbc.update("INSERT INTO users (id, phone, password_hash, display_name) VALUES (?, ?, 'x', 'Sync')",
                userId, "+84912345678");
        insertConversation(jdbc, firstConversationId, userId, "populated-a");
        insertConversation(jdbc, secondConversationId, userId, "populated-b");
        insertConversation(jdbc, emptyConversationId, userId, "empty");
        long firstSeq = insertMessage(jdbc, firstMessageId, firstConversationId, userId,
                "kept-after-backfill");
        insertMessage(jdbc, tombstoneMessageId, firstConversationId, userId, "tombstone-content");
        insertMessage(jdbc, secondConversationMessageId, secondConversationId, userId,
                "second-conversation");
        jdbc.update("UPDATE messages SET deleted_at = now(), content = NULL WHERE id = ?", tombstoneMessageId);
        return new Fixture(firstConversationId, secondConversationId, emptyConversationId,
                firstMessageId, tombstoneMessageId, secondConversationMessageId, firstSeq);
    }

    private static void insertConversation(JdbcTemplate jdbc, UUID conversationId, UUID userId, String title) {
        jdbc.update("""
                INSERT INTO conversations (id, type, title, created_by)
                VALUES (?, 'GROUP', ?, ?)
                """, conversationId, title, userId);
    }

    private static long insertMessage(JdbcTemplate jdbc, UUID messageId, UUID conversationId,
                                      UUID userId, String content) {
        return jdbc.queryForObject("""
                INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type, content)
                VALUES (?, ?, ?, ?, 'TEXT', ?)
                RETURNING seq
                """, Long.class, messageId, conversationId, userId, UUID.randomUUID(), content);
    }

    private static Flyway flyway(PostgresTestDatabase database) {
        return Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .load();
    }

    private static Flyway flyway(PostgresTestDatabase database, String target) {
        return Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForObject("""
                SELECT count(*) > 0
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Boolean.class, table, column);
    }

    private static boolean indexExists(JdbcTemplate jdbc, String index) {
        return jdbc.queryForObject(
                "SELECT count(*) > 0 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Boolean.class, index);
    }

    private record Fixture(UUID populatedConversationId, UUID secondConversationId,
                           UUID emptyConversationId, UUID firstMessageId,
                           UUID tombstoneMessageId, UUID secondConversationMessageId, long firstSeq) {
    }
}
