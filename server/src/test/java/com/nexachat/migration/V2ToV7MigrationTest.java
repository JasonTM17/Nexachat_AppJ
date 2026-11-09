package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import com.nexachat.testutil.PostgresTestDatabase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Populated-predecessor coverage for each data-altering V2–V7 migration. */
class V2ToV7MigrationTest {

    @Test
    void v2BackfillsAttachmentOwnerAndKeepsLegacyRows() throws Exception {
        onScratch("v2", dataSource -> {
            migrate(dataSource, "1");
            LegacyRows rows = seedCore(dataSource);
            UUID attachmentId = seedV1Attachment(dataSource, rows);
            migrate(dataSource, "2");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertLegacyRows(connection, rows);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT owner_id, message_id FROM message_attachments WHERE id = ?")) {
                    query.setObject(1, attachmentId);
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject("owner_id", UUID.class)).isEqualTo(rows.userId());
                        assertThat(result.getObject("message_id", UUID.class)).isEqualTo(rows.messageId());
                    }
                }

                UUID unboundId = UUID.randomUUID();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO message_attachments"
                                + " (id, owner_id, storage_key, original_name, mime_type, size_bytes)"
                                + " VALUES (?, ?, 'v2-unbound', 'new.png', 'image/png', 10)")) {
                    insert.setObject(1, unboundId);
                    insert.setObject(2, rows.userId());
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO message_attachments"
                                + " (owner_id, storage_key, original_name, mime_type, size_bytes)"
                                + " VALUES ('" + UUID.randomUUID() + "', 'v2-invalid-owner',"
                                + " 'invalid.png', 'image/png', 10)"))
                        .isInstanceOf(SQLException.class);
            }
        });
    }

    @Test
    void v3PreservesLegacyRowsAndAddsInteractionKeys() throws Exception {
        onScratch("v3", dataSource -> {
            migrate(dataSource, "2");
            LegacyRows rows = seedCore(dataSource);
            migrate(dataSource, "3");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertLegacyRows(connection, rows);
                String reaction = "INSERT INTO message_reactions (message_id, user_id, emoji) VALUES ('"
                        + rows.messageId() + "', '" + rows.userId() + "', '👍')";
                assertThat(statement.executeUpdate(reaction)).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(reaction))
                        .isInstanceOf(SQLException.class);

                String pin = "INSERT INTO pinned_messages (conversation_id, message_id, pinned_by) VALUES ('"
                        + rows.conversationId() + "', '" + rows.messageId() + "', '" + rows.userId() + "')";
                assertThat(statement.executeUpdate(pin)).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate(pin))
                        .isInstanceOf(SQLException.class);
            }
        });
    }

    @Test
    void v4PreservesUsersAndDefaultsPresenceForExistingRows() throws Exception {
        onScratch("v4", dataSource -> {
            migrate(dataSource, "3");
            LegacyRows rows = seedCore(dataSource);
            migrate(dataSource, "4");

            try (Connection connection = dataSource.getConnection()) {
                assertLegacyRows(connection, rows);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT show_presence, last_seen_at FROM users WHERE id = ?")) {
                    query.setObject(1, rows.userId());
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean("show_presence")).isTrue();
                        assertThat(result.getTimestamp("last_seen_at")).isNull();
                    }
                }
            }
        });
    }

    @Test
    void v5PreservesLegacyRowsAndRejectsInvalidCallState() throws Exception {
        onScratch("v5", dataSource -> {
            migrate(dataSource, "4");
            LegacyRows rows = seedCore(dataSource);
            migrate(dataSource, "5");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertLegacyRows(connection, rows);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO call_records"
                                + " (call_id, conversation_id, caller_id, callee_id, state, started_at)"
                                + " VALUES ('" + UUID.randomUUID() + "', '" + rows.conversationId()
                                + "', '" + rows.userId() + "', '" + rows.peerId()
                                + "', 'INVALID', now())"))
                        .isInstanceOf(SQLException.class);
            }
        });
    }

    @Test
    void v6PreservesLegacyRowsAndAddsSummaryConstraints() throws Exception {
        onScratch("v6", dataSource -> {
            migrate(dataSource, "5");
            LegacyRows rows = seedCore(dataSource);
            migrate(dataSource, "6");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertLegacyRows(connection, rows);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT ai_summary_consent FROM users WHERE id = ?")) {
                    query.setObject(1, rows.userId());
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isFalse();
                    }
                }

                String invalidWindow = "INSERT INTO message_summaries"
                        + " (conversation_id, from_seq, to_seq, content_hash, provider, language, payload_json, expires_at)"
                        + " VALUES ('" + rows.conversationId() + "', 5, 1, '" + "a".repeat(64)
                        + "', 'LOCAL', 'EN', '{}'::jsonb, now() + interval '1 hour')";
                assertThatThrownBy(() -> statement.executeUpdate(invalidWindow))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO summary_request_limits (user_id, window_type, window_start, request_count)"
                                + " VALUES ('" + rows.userId() + "', 'MINUTE', now(), 0)"))
                        .isInstanceOf(SQLException.class);
            }
        });
    }

    @Test
    void v7PreservesLegacyRowsAndDefaultsDeliveryWatermark() throws Exception {
        onScratch("v7", dataSource -> {
            migrate(dataSource, "6");
            LegacyRows rows = seedCore(dataSource);
            migrate(dataSource, "7");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertLegacyRows(connection, rows);
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT last_delivered_seq FROM conversation_members"
                                + " WHERE conversation_id = ? AND user_id = ?")) {
                    query.setObject(1, rows.conversationId());
                    query.setObject(2, rows.userId());
                    try (ResultSet result = query.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getLong(1)).isZero();
                    }
                }

                String token = "v7-token-" + UUID.randomUUID();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO user_device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')")) {
                    insert.setObject(1, rows.userId());
                    insert.setString(2, token);
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO user_device_tokens (user_id, token, platform) VALUES ('"
                                + rows.peerId() + "', '" + token + "', 'ANDROID')"))
                        .isInstanceOf(SQLException.class);
                assertThat(indexExists(statement, "idx_messages_delta_sync")).isTrue();
            }
        });
    }

    private static void migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static LegacyRows seedCore(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = insertUser(connection, "Legacy owner");
            UUID peerId = insertUser(connection, "Legacy peer");
            UUID conversationId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO conversations (type, direct_key, created_by) VALUES ('DIRECT', ?, ?) RETURNING id")) {
                insert.setString(1, "migration:" + UUID.randomUUID());
                insert.setObject(2, userId);
                try (ResultSet result = insert.executeQuery()) {
                    result.next();
                    conversationId = result.getObject(1, UUID.class);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'ADMIN'), (?, ?, 'MEMBER')")) {
                insert.setObject(1, conversationId);
                insert.setObject(2, userId);
                insert.setObject(3, conversationId);
                insert.setObject(4, peerId);
                assertThat(insert.executeUpdate()).isEqualTo(2);
            }
            UUID messageId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)"
                            + " VALUES (?, ?, ?, 'TEXT', 'legacy row') RETURNING id")) {
                insert.setObject(1, conversationId);
                insert.setObject(2, userId);
                insert.setObject(3, UUID.randomUUID());
                try (ResultSet result = insert.executeQuery()) {
                    result.next();
                    messageId = result.getObject(1, UUID.class);
                }
            }
            return new LegacyRows(userId, peerId, conversationId, messageId);
        }
    }

    private static UUID insertUser(Connection connection, String displayName) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (phone, password_hash, display_name) VALUES (?, ?, ?) RETURNING id")) {
            insert.setString(1, "+849" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L)));
            insert.setString(2, "x".repeat(60));
            insert.setString(3, displayName);
            try (ResultSet result = insert.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static UUID seedV1Attachment(DataSource dataSource, LegacyRows rows) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO message_attachments"
                             + " (message_id, storage_key, original_name, mime_type, size_bytes)"
                             + " VALUES (?, 'v2-legacy', 'old.png', 'image/png', 10) RETURNING id")) {
            insert.setObject(1, rows.messageId());
            try (ResultSet result = insert.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void assertLegacyRows(Connection connection, LegacyRows rows) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM users WHERE id = ?", rows.userId())).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM conversations WHERE id = ?", rows.conversationId()))
                .isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM messages WHERE id = ?", rows.messageId())).isEqualTo(1);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id IN (?, ?)")) {
            query.setObject(1, rows.conversationId());
            query.setObject(2, rows.userId());
            query.setObject(3, rows.peerId());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
    }

    private static int count(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, id);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static boolean indexExists(Statement statement, String indexName) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '" + indexName + "'")) {
            return result.next();
        }
    }

    private static void onScratch(String label, DatabaseCheck check) throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v2v7-" + label)) {
            DataSource dataSource = testDatabase.dataSource();
            check.run(dataSource);
            migrate(dataSource, null);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .validate();
        }
    }

    @FunctionalInterface
    private interface DatabaseCheck {
        void run(DataSource dataSource) throws Exception;
    }

    private record LegacyRows(UUID userId, UUID peerId, UUID conversationId, UUID messageId) {
    }
}
