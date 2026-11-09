package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V19 applies to a populated V16 database without touching existing
 * rows, creates the two partial delta-sync indexes, and that the planner
 * actually uses them for the edited/deleted scans. The scratch database lives
 * beside the shared test database and is dropped afterwards.
 */
class V19MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v19AppliesOnPopulatedV16DatabaseAndIndexesDeltaScans() throws Exception {
        String database = "nexachat_mig_v19_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("16")
                    .load()
                    .migrate();

            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID conversation = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + alice + "', '+84930000001', 'x', 'Alice')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + bob + "', '+84930000002', 'x', 'Bob')");
                statement.executeUpdate("INSERT INTO conversations (id, type, title, created_by)"
                        + " VALUES ('" + conversation + "', 'GROUP', 'Team', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + conversation + "', '" + alice + "', 'ADMIN')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + conversation + "', '" + bob + "', 'MEMBER')");

                // Enough rows that a full conversation scan is the expensive plan.
                statement.executeUpdate(
                        "INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)"
                                + " SELECT '" + conversation + "', '" + alice + "', gen_random_uuid(), 'TEXT',"
                                + " 'Message ' || g FROM generate_series(1, 5000) AS g");

                // A handful of touched rows: two edited, three soft-deleted.
                statement.executeUpdate(
                        "UPDATE messages SET edited_at = now() - interval '1 day'"
                                + " WHERE conversation_id = '" + conversation + "' AND seq % 2000 = 0");
                statement.executeUpdate(
                        "UPDATE messages SET deleted_at = now() - interval '1 day'"
                                + " WHERE conversation_id = '" + conversation + "' AND seq % 1500 = 0");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(indexExists(statement, "idx_messages_conv_edited")).isTrue();
                assertThat(indexExists(statement, "idx_messages_conv_deleted")).isTrue();

                // Existing chat rows survive the migration untouched.
                assertThat(count(statement, "users")).isEqualTo(2);
                assertThat(count(statement, "conversations")).isEqualTo(1);
                assertThat(count(statement, "messages")).isEqualTo(5000);

                // Fresh statistics so the planner costings below are stable.
                statement.executeUpdate("ANALYZE messages");

                // The delta queries still return exactly the touched rows.
                assertThat(uuids(statement,
                        "SELECT id FROM messages WHERE conversation_id = '" + conversation
                                + "' AND edited_at IS NOT NULL ORDER BY seq"))
                        .hasSize(2);
                assertThat(uuids(statement,
                        "SELECT id FROM messages WHERE conversation_id = '" + conversation
                                + "' AND deleted_at IS NOT NULL ORDER BY seq"))
                        .hasSize(3);

                // The partial indexes are the plan the planner picks for both
                // delta scans once sequential scans are off the table.
                statement.executeUpdate("SET enable_seqscan = off");
                assertThat(planOf(statement,
                        "SELECT id FROM messages WHERE conversation_id = '" + conversation
                                + "' AND seq <= (SELECT max(seq) FROM messages WHERE conversation_id = '"
                                + conversation + "') AND edited_at > now() - interval '7 days'"
                                + " AND deleted_at IS NULL ORDER BY edited_at"))
                        .contains("idx_messages_conv_edited");
                assertThat(planOf(statement,
                        "SELECT id FROM messages WHERE conversation_id = '" + conversation
                                + "' AND seq <= (SELECT max(seq) FROM messages WHERE conversation_id = '"
                                + conversation + "') AND deleted_at > now() - interval '7 days'"))
                        .contains("idx_messages_conv_deleted");
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

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long count(Statement statement, String table) throws SQLException {
        ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table);
        result.next();
        return result.getLong(1);
    }

    private static List<UUID> uuids(Statement statement, String sql) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        ResultSet result = statement.executeQuery(sql);
        while (result.next()) {
            ids.add(UUID.fromString(result.getString(1)));
        }
        return ids;
    }

    private static String planOf(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql);
        StringBuilder plan = new StringBuilder();
        while (result.next()) {
            plan.append(result.getString(1)).append('\n');
        }
        return plan.toString();
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
