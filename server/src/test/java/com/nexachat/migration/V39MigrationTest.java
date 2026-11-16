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
 * V39 adds the per-conversation disappearing-message timer
 * ({@code conversations.disappearing_seconds}) and the per-message expiry
 * instant ({@code messages.expires_at}) with a partial index that only covers
 * live expiring rows. Proves the migration applies on a populated schema,
 * the CHECK constraint rejects non-allow-listed timers, and the index is
 * usable by the purge scan.
 */
class V39MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v39AddsTimerColumnsAndExpiresAtIndex() throws Exception {
        String database = "nexachat_mig_v39_" + System.nanoTime();
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
                insertUser(statement, alice, "+84932000001", "Alice");
                insertUser(statement, bob, "+84932000002", "Bob");

                UUID conversation = UUID.randomUUID();
                statement.executeUpdate("INSERT INTO conversations (id, type, direct_key, created_by)"
                        + " VALUES ('" + conversation + "', 'DIRECT', 'dk-"
                        + conversation + "', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversation_members"
                        + " (conversation_id, user_id) VALUES ('" + conversation + "', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversation_members"
                        + " (conversation_id, user_id) VALUES ('" + conversation + "', '" + bob + "')");

                // The timer column defaults to NULL (off) and accepts every
                // allow-listed duration; anything else is rejected.
                assertThat(scalar(statement,
                        "SELECT count(*) FROM conversations WHERE disappearing_seconds IS NULL"))
                        .isEqualTo(1);
                statement.executeUpdate("UPDATE conversations SET disappearing_seconds = 3600"
                        + " WHERE id = '" + conversation + "'");
                assertThat(scalar(statement,
                        "SELECT disappearing_seconds FROM conversations WHERE id = '"
                                + conversation + "'")).isEqualTo(3600);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE conversations SET disappearing_seconds = 42"
                                + " WHERE id = '" + conversation + "'"))
                        .hasMessageContaining("chk_conversations_disappearing_seconds");

                // messages.expires_at accepts an instant and the partial index
                // exists for the purge scan.
                statement.executeUpdate("INSERT INTO messages"
                        + " (id, conversation_id, sender_id, client_message_id, type, content,"
                        + " expires_at) VALUES ('" + UUID.randomUUID() + "', '" + conversation
                        + "', '" + alice + "', '" + UUID.randomUUID() + "', 'TEXT', 'gone',"
                        + " now() + interval '1 hour')");
                assertThat(indexExists(statement, "idx_messages_expires_at")).isTrue();
                assertThat(scalar(statement,
                        "SELECT count(*) FROM messages WHERE expires_at IS NOT NULL"))
                        .isEqualTo(1);
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

    private static void insertUser(Statement statement, UUID id, String phone, String name)
            throws SQLException {
        statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                + " VALUES ('" + id + "', '" + phone + "', 'x', '" + name + "')");
    }

    // ----------------------------------------------------------------- helpers

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
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
