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

/** Proves V17 preserves a populated V16 database and constrains mention rows. */
class V17MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v17AppliesToPopulatedV16DatabaseAndConstrainsMentionTargets() throws Exception {
        String database = "nexachat_mig_v17_" + System.nanoTime();
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
            UUID carol = UUID.randomUUID();
            UUID groupOne = UUID.randomUUID();
            UUID groupTwo = UUID.randomUUID();
            UUID message = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + alice + "', '+84950000001', 'x', 'Alice')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + bob + "', '+84950000002', 'x', 'Bob')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + carol + "', '+84950000003', 'x', 'Carol')");
                statement.executeUpdate("INSERT INTO conversations (id, type, title, created_by) VALUES ('"
                        + groupOne + "', 'GROUP', 'One', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversations (id, type, title, created_by) VALUES ('"
                        + groupTwo + "', 'GROUP', 'Two', '" + alice + "')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + groupOne + "', '" + alice + "', 'ADMIN')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + groupOne + "', '" + bob + "', 'MEMBER')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + groupTwo + "', '" + alice + "', 'ADMIN')");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + groupTwo + "', '" + carol + "', 'MEMBER')");
                statement.executeUpdate("INSERT INTO messages (id, conversation_id, sender_id,"
                        + " client_message_id, type, content) VALUES ('" + message + "', '"
                        + groupOne + "', '" + alice + "', gen_random_uuid(), 'TEXT', 'hello')");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(tableExists(statement, "message_mentions")).isTrue();
                assertThat(columnExists(statement, "messages", "mention_all")).isTrue();
                assertThat(count(statement, "users")).isEqualTo(3);
                assertThat(count(statement, "conversations")).isEqualTo(2);
                assertThat(count(statement, "messages")).isEqualTo(1);
                assertThat(singleBoolean(statement,
                        "SELECT mention_all FROM messages WHERE id = '" + message + "'")).isFalse();

                statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + bob + "', 0, 4)");
                assertThat(count(statement, "message_mentions")).isEqualTo(1);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + bob + "', 5, 2)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupTwo + "', '" + carol + "', 0, 4)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + carol + "', 0, 4)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + alice + "', -1, 4)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + alice + "', 2147483647, 1)"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("UPDATE messages SET mention_all = TRUE WHERE id = '" + message + "'");
                assertThat(singleBoolean(statement,
                        "SELECT mention_all FROM messages WHERE id = '" + message + "'")).isTrue();

                // Removing a group member removes their structured mention rows.
                statement.executeUpdate("DELETE FROM conversation_members WHERE conversation_id = '"
                        + groupOne + "' AND user_id = '" + bob + "'");
                assertThat(count(statement, "message_mentions")).isZero();
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + bob + "', 0, 4)"))
                        .isInstanceOf(SQLException.class);
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " VALUES ('" + groupOne + "', '" + bob + "', 'MEMBER')");
                statement.executeUpdate("INSERT INTO message_mentions"
                        + " (message_id, conversation_id, user_id, mention_offset, mention_length) VALUES ('"
                        + message + "', '" + groupOne + "', '" + bob + "', 0, 4)");
                statement.executeUpdate("DELETE FROM messages WHERE id = '" + message + "'");
                assertThat(count(statement, "message_mentions")).isZero();
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

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        ResultSet result = statement.executeQuery("SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = '" + table + "'");
        return result.next();
    }

    private static boolean columnExists(Statement statement, String table, String column) throws SQLException {
        ResultSet result = statement.executeQuery("SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = '" + table
                + "' AND column_name = '" + column + "'");
        return result.next();
    }

    private static long count(Statement statement, String table) throws SQLException {
        ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table);
        result.next();
        return result.getLong(1);
    }

    private static boolean singleBoolean(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getBoolean(1);
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
