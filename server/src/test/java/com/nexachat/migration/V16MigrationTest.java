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
 * Proves V16 applies to a populated V15 database without touching existing
 * rows, adds the per-member conversation pin state and the account-level
 * "seen" privacy switch, and enforces the pin timestamp invariant.
 *
 * <p>The scratch database lives beside the shared test database and is dropped
 * afterwards; the shared database is only used as the maintenance connection.
 */
class V16MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v16AppliesOnPopulatedV15DatabaseAndKeepsExistingRowsUnpinnedAndSeen() throws Exception {
        String database = "nexachat_mig_v16_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("15")
                    .load()
                    .migrate();

            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID conversation = UUID.randomUUID();
            UUID message = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + alice + "', '+84940000001', 'x', 'Alice')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + bob + "', '+84940000002', 'x', 'Bob')");
                statement.executeUpdate("INSERT INTO conversations (id, type, title, created_by)"
                        + " VALUES ('" + conversation + "', 'GROUP', 'Team', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + conversation + "', '" + alice + "', 'ADMIN')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + conversation + "', '" + bob + "', 'MEMBER')");
                statement.executeUpdate("INSERT INTO messages (id, conversation_id, sender_id,"
                        + " client_message_id, type, content) VALUES ('" + message + "', '"
                        + conversation + "', '" + alice + "', gen_random_uuid(), 'TEXT', 'hello')");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertColumn(statement, "conversation_members", "pinned",
                        "boolean", "NO", "false");
                assertColumn(statement, "conversation_members", "pinned_at",
                        "timestamp with time zone", "YES", null);
                assertColumn(statement, "users", "show_seen", "boolean", "NO", "true");
                assertThat(indexExists(statement, "idx_conversation_members_user_pinned")).isTrue();

                // Existing rows keep their defaults: no pin, seen still shared.
                assertThat(countWhere(statement,
                        "SELECT COUNT(*) FROM conversation_members"
                                + " WHERE pinned = FALSE AND pinned_at IS NULL")).isEqualTo(2L);
                assertThat(countWhere(statement,
                        "SELECT COUNT(*) FROM users WHERE show_seen = TRUE")).isEqualTo(2L);
                assertThat(countWhere(statement, "SELECT COUNT(*) FROM messages")).isEqualTo(1L);

                // Pin state accepts a timestamp and the invariant rejects
                // half-written states in both directions.
                statement.executeUpdate("UPDATE conversation_members"
                        + " SET pinned = TRUE, pinned_at = now()"
                        + " WHERE user_id = '" + alice + "'");
                assertThat(countWhere(statement,
                        "SELECT COUNT(*) FROM conversation_members"
                                + " WHERE user_id = '" + alice + "'"
                                + " AND pinned = TRUE AND pinned_at IS NOT NULL")).isEqualTo(1L);

                assertThatThrownBy(() -> statement.executeUpdate("UPDATE conversation_members"
                        + " SET pinned = TRUE, pinned_at = NULL"
                        + " WHERE user_id = '" + bob + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE conversation_members"
                        + " SET pinned = FALSE, pinned_at = now()"
                        + " WHERE user_id = '" + bob + "'"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("UPDATE users SET show_seen = FALSE WHERE id = '" + bob + "'");
                assertThat(countWhere(statement,
                        "SELECT COUNT(*) FROM users WHERE id = '" + bob + "' AND show_seen = FALSE"))
                        .isEqualTo(1L);
            }

            // V1..V15 were not modified: Flyway validation of the applied
            // checksums against the classpath must succeed.
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

    private static void assertColumn(Statement statement, String table, String column, String type,
                                     String nullable, String defaultValue) throws SQLException {
        ResultSet columns = statement.executeQuery(
                "SELECT data_type, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = 'public'"
                        + "   AND table_name = '" + table + "'"
                        + "   AND column_name = '" + column + "'");
        assertThat(columns.next())
                .as("%s.%s must exist after V16", table, column).isTrue();
        assertThat(columns.getString("data_type")).isEqualTo(type);
        assertThat(columns.getString("is_nullable")).isEqualTo(nullable);
        if (defaultValue == null) {
            assertThat(columns.getString("column_default")).isNull();
        } else {
            assertThat(columns.getString("column_default")).isEqualTo(defaultValue);
        }
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long countWhere(Statement statement, String sql) throws SQLException {
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
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
            statement.executeUpdate("CREATE DATABASE " + database);
        }
    }

    private static void dropDatabase(String database) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
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
