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
 * Proves V15 applies to a populated V11 database without touching existing
 * rows, then creates the poll tables, the version sequence and the vote
 * uniqueness/cascade invariants. The scratch database lives beside the shared
 * test database and is dropped afterwards.
 */
class V15MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v15AppliesOnPopulatedV11DatabaseAndCascadesPollRows() throws Exception {
        String database = "nexachat_mig_v15_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("11")
                    .load()
                    .migrate();

            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID conversation = UUID.randomUUID();
            UUID message = UUID.randomUUID();
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
                assertThat(tableExists(statement, "polls")).isTrue();
                assertThat(tableExists(statement, "poll_options")).isTrue();
                assertThat(tableExists(statement, "poll_votes")).isTrue();
                assertThat(sequenceExists(statement, "poll_version_seq")).isTrue();
                assertThat(indexExists(statement, "idx_polls_conversation_created")).isTrue();
                assertThat(indexExists(statement, "idx_polls_conversation_version")).isTrue();
                assertThat(indexExists(statement, "idx_poll_votes_poll_user")).isTrue();

                // Existing chat rows survive the migration untouched.
                assertThat(count(statement, "users")).isEqualTo(2);
                assertThat(count(statement, "conversations")).isEqualTo(1);
                assertThat(count(statement, "messages")).isEqualTo(1);

                statement.executeUpdate("INSERT INTO polls (id, conversation_id, created_by,"
                        + " question, multiple_choice, anonymous)"
                        + " VALUES (gen_random_uuid(), '" + conversation + "', '" + alice + "',"
                        + " 'Lunch?', false, false)");
                UUID poll = singleUuid(statement, "SELECT id FROM polls");
                long version = singleLong(statement, "SELECT version FROM polls WHERE id = '" + poll + "'");
                assertThat(version).isPositive();
                assertThat(singleLong(statement,
                        "SELECT last_value FROM poll_version_seq")).isGreaterThanOrEqualTo(version);

                statement.executeUpdate("INSERT INTO poll_options (id, poll_id, position, label)"
                        + " VALUES (gen_random_uuid(), '" + poll + "', 0, 'A')");
                statement.executeUpdate("INSERT INTO poll_options (id, poll_id, position, label)"
                        + " VALUES (gen_random_uuid(), '" + poll + "', 1, 'B')");
                UUID option = singleUuid(statement,
                        "SELECT id FROM poll_options WHERE poll_id = '" + poll + "' AND position = 0");

                statement.executeUpdate("INSERT INTO poll_votes (poll_id, option_id, user_id)"
                        + " VALUES ('" + poll + "', '" + option + "', '" + bob + "')");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO poll_votes (poll_id, option_id, user_id)"
                                + " VALUES ('" + poll + "', '" + option + "', '" + bob + "')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO poll_options (id, poll_id, position, label)"
                                + " VALUES (gen_random_uuid(), '" + poll + "', 0, 'duplicate position')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO poll_options (id, poll_id, position, label)"
                                + " VALUES (gen_random_uuid(), '" + poll + "', 2, '   ')"))
                        .isInstanceOf(SQLException.class);

                // Deleting the conversation cascades polls -> options -> votes.
                statement.executeUpdate("DELETE FROM conversations WHERE id = '" + conversation + "'");
                assertThat(count(statement, "polls")).isZero();
                assertThat(count(statement, "poll_options")).isZero();
                assertThat(count(statement, "poll_votes")).isZero();
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

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        ResultSet tables = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name = '" + table + "'");
        return tables.next();
    }

    private static boolean sequenceExists(Statement statement, String sequence) throws SQLException {
        ResultSet sequences = statement.executeQuery(
                "SELECT 1 FROM information_schema.sequences WHERE sequence_schema = 'public'"
                        + " AND sequence_name = '" + sequence + "'");
        return sequences.next();
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long count(Statement statement, String table) throws SQLException {
        return singleLong(statement, "SELECT count(*) FROM " + table);
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
    }

    private static UUID singleUuid(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return UUID.fromString(result.getString(1));
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
