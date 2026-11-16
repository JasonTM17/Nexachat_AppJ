package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V38 creates {@code user_blocks}, {@code reports}, {@code message_stars}
 * and {@code messages.peer_suppressed}. Proves the migration applies on a
 * populated schema and that the safety invariants hold at the storage
 * layer: block dedupe + no self-blocks, one OPEN report per
 * (reporter, target), star dedupe + message cascade, and the suppression
 * flag's non-null default on pre-existing rows.
 */
class V38MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v38CreatesSafetySchemaAndEnforcesItsInvariants() throws Exception {
        String database = "nexachat_mig_v38_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                insertUser(statement, alice, "+84938000001", "Alice");
                insertUser(statement, bob, "+84938000002", "Bob");

                // ------------------------------------------------ user_blocks
                statement.executeUpdate("INSERT INTO user_blocks (blocker_id, blocked_id)"
                        + " VALUES ('" + alice + "', '" + bob + "')");
                // Both directions may coexist — blocking is directed, not mutual.
                statement.executeUpdate("INSERT INTO user_blocks (blocker_id, blocked_id)"
                        + " VALUES ('" + bob + "', '" + alice + "')");
                // A repeat edge is a no-op for ON CONFLICT writers, and the PK
                // still rejects a plain duplicate.
                assertThat(statement.executeUpdate(
                        "INSERT INTO user_blocks (blocker_id, blocked_id)"
                                + " VALUES ('" + alice + "', '" + bob + "')"
                                + " ON CONFLICT DO NOTHING")).isZero();
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO user_blocks (blocker_id, blocked_id)"
                                + " VALUES ('" + alice + "', '" + alice + "')"))
                        .as("self-blocks are rejected by the CHECK")
                        .isInstanceOf(SQLException.class);
                assertThat(scalar(statement, "SELECT count(*) FROM user_blocks")).isEqualTo(2);

                // ------------------------------------------- peer_suppressed
                statement.executeUpdate(
                        "INSERT INTO conversations (type, title, created_by, change_version)"
                                + " VALUES ('GROUP', 'g', '" + alice + "', 0)");
                UUID conversation = scalarId(statement,
                        "SELECT id FROM conversations LIMIT 1");
                statement.executeUpdate(
                        "INSERT INTO conversation_members (conversation_id, user_id, role)"
                                + " VALUES ('" + conversation + "', '" + alice + "', 'MEMBER'),"
                                + " ('" + conversation + "', '" + bob + "', 'MEMBER')");
                UUID plainMessage = insertMessage(statement, conversation, alice, false);
                UUID suppressedMessage = insertMessage(statement, conversation, bob, true);
                assertThat(scalar(statement, "SELECT count(*) FROM messages"
                        + " WHERE peer_suppressed = false AND id = '" + plainMessage + "'"))
                        .as("pre-existing-style rows default to visible")
                        .isEqualTo(1);
                assertThat(scalar(statement, "SELECT count(*) FROM messages"
                        + " WHERE peer_suppressed = true AND id = '" + suppressedMessage + "'"))
                        .isEqualTo(1);
                assertThat(columnDefault(statement, "messages", "peer_suppressed"))
                        .isEqualTo("false");

                // ----------------------------------------------------- reports
                insertReport(statement, alice, "MESSAGE", suppressedMessage, "SPAM", "OPEN");
                assertThatThrownBy(() -> insertReport(
                        statement, alice, "MESSAGE", suppressedMessage, "OTHER", "OPEN"))
                        .as("the partial unique index refuses a second OPEN report")
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertReport(
                        statement, alice, "MESSAGE", suppressedMessage, "BOGUS", "OPEN"))
                        .as("reason is a closed enum")
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertReport(
                        statement, alice, "GROUP", suppressedMessage, "SPAM", "OPEN"))
                        .as("target_type is a closed enum")
                        .isInstanceOf(SQLException.class);
                // Once reviewed, a NEW report on the same target may open.
                statement.executeUpdate("UPDATE reports SET status = 'REVIEWED'");
                insertReport(statement, alice, "MESSAGE", suppressedMessage, "HARASSMENT", "OPEN");
                assertThat(scalar(statement, "SELECT count(*) FROM reports")).isEqualTo(2);

                // ----------------------------------------------- message_stars
                statement.executeUpdate("INSERT INTO message_stars (user_id, message_id)"
                        + " VALUES ('" + alice + "', '" + plainMessage + "')");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO message_stars (user_id, message_id)"
                                + " VALUES ('" + alice + "', '" + plainMessage + "')"))
                        .as("a repeat star conflicts on the PK")
                        .isInstanceOf(SQLException.class);
                // Recalling the message cascades its stars away.
                statement.executeUpdate("DELETE FROM messages WHERE id = '" + plainMessage + "'");
                assertThat(scalar(statement, "SELECT count(*) FROM message_stars")).isZero();
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .validate();
        } finally {
            dropDatabase(database);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void insertUser(Statement statement, UUID id, String phone, String name)
            throws SQLException {
        statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                + " VALUES ('" + id + "', '" + phone + "', 'x', '" + name + "')");
    }

    private static UUID insertMessage(Statement statement, UUID conversationId, UUID sender,
                                      boolean peerSuppressed) throws SQLException {
        ResultSet row = statement.executeQuery(
                "INSERT INTO messages (conversation_id, sender_id, client_message_id, type,"
                        + " content, peer_suppressed)"
                        + " VALUES ('" + conversationId + "', '" + sender + "', '"
                        + UUID.randomUUID() + "', 'TEXT', 'fixture', " + peerSuppressed + ")"
                        + " RETURNING id");
        row.next();
        return (UUID) row.getObject(1);
    }

    private static void insertReport(Statement statement, UUID reporter, String targetType,
                                     UUID targetId, String reason, String status)
            throws SQLException {
        statement.executeUpdate(
                "INSERT INTO reports (reporter_id, target_type, target_id, reason, status)"
                        + " VALUES ('" + reporter + "', '" + targetType + "', '" + targetId
                        + "', '" + reason + "', '" + status + "')");
    }

    private static UUID scalarId(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return (UUID) result.getObject(1);
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
    }

    private static String columnDefault(Statement statement, String table, String column)
            throws SQLException {
        ResultSet result = statement.executeQuery(
                "SELECT column_default FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "' AND column_name = '" + column + "'");
        result.next();
        return result.getString(1);
    }

    private static DataSource dataSource(String database) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(serverUrl() + database);
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        return dataSource;
    }

    private static String serverUrl() {
        return JDBC_URL.substring(0, JDBC_URL.lastIndexOf('/') + 1);
    }

    private static void createDatabase(String database) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.executeUpdate("CREATE DATABASE " + database);
        }
    }

    private static void dropDatabase(String database) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Connection adminConnection() throws SQLException {
        java.sql.Driver driver = new org.postgresql.Driver();
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("user", DB_USER);
        properties.setProperty("password", DB_PASSWORD);
        return driver.connect(serverUrl() + "postgres", properties);
    }
}
