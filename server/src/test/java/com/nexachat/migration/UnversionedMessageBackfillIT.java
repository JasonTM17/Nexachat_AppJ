package com.nexachat.migration;

import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V35 backfills revisions onto messages written before the sync-v2
 * change-cursor contract existed ({@code change_version <= 0}) and raises
 * {@code conversations.change_version} to cover them. Fixtures are seeded on
 * the V34 schema, then Flyway applies the real migration file.
 */
class UnversionedMessageBackfillIT {

    private static final String V35_SCRIPT = "db/migration/V35__backfill_unversioned_messages.sql";

    private static final UUID USER_ONE = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_TWO = UUID.fromString("00000000-0000-0000-0000-000000000102");

    private static final UUID CONV_ALL_ZERO = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID CONV_HEALTHY = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID CONV_MIXED = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID CONV_EMPTY = UUID.fromString("00000000-0000-0000-0000-000000000204");
    private static final UUID CONV_TOMBSTONE = UUID.fromString("00000000-0000-0000-0000-000000000205");
    private static final UUID CONV_CURSOR_AHEAD = UUID.fromString("00000000-0000-0000-0000-000000000206");

    @Test
    void backfillAssignsMonotonicRevisionsAndRaisesConversationCursors() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("v35-backfill")) {
            Flyway flyway = flyway(database, "34");
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            Fixtures fixtures = seedFixtures(jdbc);

            // The healthy conversation and the empty one must survive untouched.
            List<Map<String, String>> healthyBefore = dumpRows(jdbc,
                    "SELECT * FROM conversations WHERE id = ?", CONV_HEALTHY);
            List<Map<String, String>> healthyMessagesBefore = dumpRows(jdbc,
                    "SELECT * FROM messages WHERE conversation_id = ? ORDER BY seq, id", CONV_HEALTHY);
            List<Map<String, String>> emptyBefore = dumpRows(jdbc,
                    "SELECT * FROM conversations WHERE id = ?", CONV_EMPTY);

            flyway(database).migrate();
            flyway(database).validate();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '35' AND success",
                    Integer.class)).isEqualTo(1);

            // Every repaired row gets a positive revision continuing after its
            // conversation's max positive revision, monotonic in (seq, id).
            assertRevisions(jdbc, CONV_ALL_ZERO, fixtures.allZero, 1, 2, 3, 4);
            assertRevisions(jdbc, CONV_HEALTHY, fixtures.healthy, 1, 2, 3);
            assertRevisions(jdbc, CONV_MIXED, fixtures.mixed, 1, 3, 2, 4, 5);
            assertRevisions(jdbc, CONV_TOMBSTONE, fixtures.tombstone, 1, 2, 3);
            assertRevisions(jdbc, CONV_CURSOR_AHEAD, fixtures.cursorAhead, 1, 2);

            // Global invariants: no unversioned rows remain, revisions stay
            // unique per conversation, and no cursor sits below its messages.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM messages WHERE change_version <= 0",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT conversation_id, change_version FROM messages
                        GROUP BY conversation_id, change_version HAVING count(*) > 1
                    ) duplicates
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM conversations c
                    WHERE c.change_version < COALESCE((
                        SELECT MAX(m.change_version) FROM messages m
                        WHERE m.conversation_id = c.id), 0)
                    """, Integer.class)).isZero();

            // Cursors land on the max revision — except the cursor-ahead
            // fixture, where GREATEST keeps the already-higher cursor so
            // repaired rows stay below it (documented edge in V35).
            assertConversationCursor(jdbc, CONV_ALL_ZERO, 4);
            assertConversationCursor(jdbc, CONV_HEALTHY, 3);
            assertConversationCursor(jdbc, CONV_MIXED, 5);
            assertConversationCursor(jdbc, CONV_EMPTY, 0);
            assertConversationCursor(jdbc, CONV_TOMBSTONE, 3);
            assertConversationCursor(jdbc, CONV_CURSOR_AHEAD, 10);

            // Tombstoned rows are repaired like live ones; the recall state
            // itself is untouched.
            assertThat(jdbc.queryForObject(
                    "SELECT deleted_at IS NOT NULL AND content IS NULL FROM messages WHERE id = ?",
                    Boolean.class, fixtures.tombstone.get(1))).isTrue();

            // Later migrations may add columns. Preserve every V34 value,
            // along with the original row count and ordering.
            assertThat(dumpRows(jdbc, "SELECT * FROM conversations WHERE id = ?", CONV_HEALTHY))
                    .zipSatisfy(healthyBefore, (after, before) -> assertThat(after).containsAllEntriesOf(before));
            assertThat(dumpRows(jdbc,
                    "SELECT * FROM messages WHERE conversation_id = ? ORDER BY seq, id", CONV_HEALTHY))
                    .zipSatisfy(healthyMessagesBefore, (after, before) -> assertThat(after).containsAllEntriesOf(before));
            assertThat(dumpRows(jdbc, "SELECT * FROM conversations WHERE id = ?", CONV_EMPTY))
                    .zipSatisfy(emptyBefore, (after, before) -> assertThat(after).containsAllEntriesOf(before));
        }
    }

    @Test
    void rerunningBackfillStatementsIsANoOpOnARepairedDatabase() throws Exception {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("v35-idempotent")) {
            Flyway flyway = flyway(database, "34");
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            seedFixtures(jdbc);
            flyway(database).migrate();

            String messagesBefore = dumpTable(jdbc, "messages", "seq, id");
            String conversationsBefore = dumpTable(jdbc, "conversations", "id");

            // Flyway already applied V35, so replay the shipped file directly:
            // every statement must report zero affected rows (the lock finds no
            // dirty conversation, both UPDATEs match nothing).
            assertThat(replayMigrationScript(database.dataSource())).containsExactly(0, 0, 0);
            assertThat(dumpTable(jdbc, "messages", "seq, id")).isEqualTo(messagesBefore);
            assertThat(dumpTable(jdbc, "conversations", "id")).isEqualTo(conversationsBefore);
        }
    }

    // ------------------------------------------------------------ statements

    /** Replays the shipped V35 file in one transaction, returning per-statement row counts. */
    private static List<Integer> replayMigrationScript(DataSource dataSource) throws Exception {
        String script;
        try (InputStream stream = UnversionedMessageBackfillIT.class.getClassLoader()
                .getResourceAsStream(V35_SCRIPT)) {
            assertThat(stream).as("V35 migration on the test classpath").isNotNull();
            script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<Integer> affected = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // pgjdbc runs multi-statement strings over the simple protocol;
                // walk each result: row count for SELECT, update count for DML.
                boolean resultSet = statement.execute(script);
                while (resultSet || statement.getUpdateCount() != -1) {
                    if (resultSet) {
                        int rows = 0;
                        try (ResultSet rs = statement.getResultSet()) {
                            while (rs.next()) {
                                rows++;
                            }
                        }
                        affected.add(rows);
                    } else {
                        affected.add(statement.getUpdateCount());
                    }
                    resultSet = statement.getMoreResults();
                }
            }
            connection.commit();
        }
        return affected;
    }

    // -------------------------------------------------------------- fixtures

    private record Fixtures(List<UUID> allZero, List<UUID> healthy, List<UUID> mixed,
                            List<UUID> tombstone, List<UUID> cursorAhead) {
    }

    private static Fixtures seedFixtures(JdbcTemplate jdbc) {
        insertUser(jdbc, USER_ONE, phonePrefix() + "000001", "FixtureOne");
        insertUser(jdbc, USER_TWO, phonePrefix() + "000002", "FixtureTwo");

        // (a) every message unversioned, cursor at 0
        insertConversation(jdbc, CONV_ALL_ZERO, "all-zero", 0);
        List<UUID> allZero = insertMessages(jdbc, CONV_ALL_ZERO, 0, 0, 0, 0);

        // (b) healthy control: positive revisions, cursor already at the max
        insertConversation(jdbc, CONV_HEALTHY, "healthy", 3);
        List<UUID> healthy = insertMessages(jdbc, CONV_HEALTHY, 1, 2, 3);

        // (c) bad rows interleaved between good ones on the (seq, id)
        // timeline, including a negative revision; cursor at the max positive
        insertConversation(jdbc, CONV_MIXED, "mixed", 2);
        List<UUID> mixed = insertMessages(jdbc, CONV_MIXED, 1, 0, 2, 0, -1);

        // (d) no messages at all
        insertConversation(jdbc, CONV_EMPTY, "empty", 0);

        // (e) a recalled (tombstoned) unversioned message among live ones
        insertConversation(jdbc, CONV_TOMBSTONE, "tombstone", 1);
        List<UUID> tombstone = insertMessages(jdbc, CONV_TOMBSTONE, 1, 0, 0);
        jdbc.update("UPDATE messages SET deleted_at = now(), content = NULL WHERE id = ?",
                tombstone.get(1));

        // (f) cursor ahead of every message revision: reachable when retention
        // deleted the newest messages while unversioned rows remained
        insertConversation(jdbc, CONV_CURSOR_AHEAD, "cursor-ahead", 10);
        List<UUID> cursorAhead = insertMessages(jdbc, CONV_CURSOR_AHEAD, 0, 0);

        return new Fixtures(allZero, healthy, mixed, tombstone, cursorAhead);
    }

    private static String phonePrefix() {
        return ItPhoneRanges.prefixFor(UnversionedMessageBackfillIT.class);
    }

    private static void insertUser(JdbcTemplate jdbc, UUID id, String phone, String name) {
        jdbc.update("INSERT INTO users (id, phone, password_hash, display_name) VALUES (?, ?, 'x', ?)",
                id, phone, name);
    }

    private static void insertConversation(JdbcTemplate jdbc, UUID id, String title, long changeVersion) {
        jdbc.update("""
                INSERT INTO conversations (id, type, title, created_by, change_version)
                VALUES (?, 'GROUP', ?, ?, ?)
                """, id, title, USER_ONE, changeVersion);
    }

    private static List<UUID> insertMessages(JdbcTemplate jdbc, UUID conversationId,
                                           long... changeVersions) {
        List<UUID> ids = new ArrayList<>();
        for (long changeVersion : changeVersions) {
            UUID sender = ids.size() % 2 == 0 ? USER_ONE : USER_TWO;
            ids.add(jdbc.queryForObject("""
                    INSERT INTO messages (conversation_id, sender_id, client_message_id, type,
                                          content, change_version)
                    VALUES (?, ?, ?, 'TEXT', ?, ?)
                    RETURNING id
                    """, UUID.class, conversationId, sender, UUID.randomUUID(),
                    "fixture-" + conversationId.toString().substring(34) + "-" + ids.size(),
                    changeVersion));
        }
        return ids;
    }

    // ------------------------------------------------------------- assertions

    private static void assertRevisions(JdbcTemplate jdbc, UUID conversationId,
                                        List<UUID> messageIds, long... expectedRevisions) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, change_version FROM messages WHERE conversation_id = ? ORDER BY seq, id",
                conversationId);
        assertThat(rows).hasSize(expectedRevisions.length);
        for (int i = 0; i < expectedRevisions.length; i++) {
            assertThat(rows.get(i).get("id")).isEqualTo(messageIds.get(i));
            assertThat(((Number) rows.get(i).get("change_version")).longValue())
                    .isEqualTo(expectedRevisions[i]);
        }
    }

    private static void assertConversationCursor(JdbcTemplate jdbc, UUID conversationId,
                                                 long expectedCursor) {
        assertThat(jdbc.queryForObject(
                "SELECT change_version FROM conversations WHERE id = ?",
                Long.class, conversationId)).isEqualTo(expectedCursor);
    }

    // ---------------------------------------------------------------- helpers

    private static List<Map<String, String>> dumpRows(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, String>> dumped = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Map<String, String> dumpedRow = new LinkedHashMap<>();
            row.forEach((key, value) -> dumpedRow.put(key, String.valueOf(value)));
            dumped.add(dumpedRow);
        }
        return dumped;
    }

    private static String dumpTable(JdbcTemplate jdbc, String table, String orderBy) {
        StringBuilder dump = new StringBuilder();
        for (Map<String, String> row : dumpRows(jdbc, "SELECT * FROM " + table + " ORDER BY " + orderBy)) {
            row.forEach((key, value) -> dump.append(key).append('=').append(value).append(' '));
            dump.append('\n');
        }
        return dump.toString();
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
}
