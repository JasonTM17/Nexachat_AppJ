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
 * Proves the per-conversation AI opt-out migration applies on an empty schema
 * and on a populated V11 schema, and that both foreign keys cascade.
 */
class V13MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v13AppliesOnFreshDatabase() throws Exception {
        String database = scratchDatabase("fresh");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(columnExists(statement, "conversation_ai_opt_outs", "conversation_id")).isTrue();
                assertThat(columnExists(statement, "conversation_ai_opt_outs", "user_id")).isTrue();
                assertThat(columnExists(statement, "conversation_ai_opt_outs", "created_at")).isTrue();

                ResultSet primaryKey = statement.executeQuery("""
                        SELECT pg_get_constraintdef(c.oid) AS definition
                        FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        WHERE t.relname = 'conversation_ai_opt_outs' AND c.contype = 'p'
                        """);
                assertThat(primaryKey.next()).isTrue();
                assertThat(primaryKey.getString(1)).contains("conversation_id", "user_id");

                ResultSet index = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'public'"
                                + "   AND tablename = 'conversation_ai_opt_outs'"
                                + "   AND indexname = 'idx_conversation_ai_opt_outs_user'");
                assertThat(index.next()).isTrue();
                assertThat(index.getString(1)).contains("user_id");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v13AppliesOnV11DatabaseWithDataAndCascades() throws Exception {
        String database = scratchDatabase("v11");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("11")
                    .load()
                    .migrate();

            UUID creatorId = UUID.randomUUID();
            UUID optedOutUserId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            UUID secondConversationId = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + creatorId + "', '+84900000011', 'x', 'A')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + optedOutUserId + "', '+84900000012', 'x', 'B')");
                statement.executeUpdate("INSERT INTO conversations (id, type, direct_key, created_by)"
                        + " VALUES ('" + conversationId + "', 'DIRECT', 'v13:a', '" + creatorId + "')");
                statement.executeUpdate("INSERT INTO conversations (id, type, direct_key, created_by)"
                        + " VALUES ('" + secondConversationId + "', 'DIRECT', 'v13:b', '" + creatorId + "')");
                statement.executeUpdate("INSERT INTO messages (id, conversation_id, sender_id,"
                        + " client_message_id, type, content) VALUES ('" + UUID.randomUUID() + "', '"
                        + conversationId + "', '" + optedOutUserId + "', gen_random_uuid(), 'TEXT',"
                        + " 'Chào bạn')");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO conversation_ai_opt_outs (conversation_id, user_id)"
                        + " VALUES ('" + conversationId + "', '" + optedOutUserId + "')");
                ResultSet count = statement.executeQuery(
                        "SELECT count(*) FROM conversation_ai_opt_outs WHERE conversation_id = '"
                                + conversationId + "'");
                count.next();
                assertThat(count.getInt(1)).isEqualTo(1);

                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO conversation_ai_opt_outs (conversation_id, user_id)"
                                + " VALUES ('" + conversationId + "', '" + optedOutUserId + "')"))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("DELETE FROM conversations WHERE id = '" + conversationId + "'");
                ResultSet afterConversation = statement.executeQuery(
                        "SELECT count(*) FROM conversation_ai_opt_outs WHERE conversation_id = '"
                                + conversationId + "'");
                afterConversation.next();
                assertThat(afterConversation.getInt(1)).isZero();

                statement.executeUpdate("INSERT INTO conversation_ai_opt_outs (conversation_id, user_id)"
                        + " VALUES ('" + secondConversationId + "', '" + optedOutUserId + "')");
                statement.executeUpdate("DELETE FROM users WHERE id = '" + optedOutUserId + "'");
                ResultSet afterUser = statement.executeQuery(
                        "SELECT count(*) FROM conversation_ai_opt_outs WHERE user_id = '"
                                + optedOutUserId + "'");
                afterUser.next();
                assertThat(afterUser.getInt(1)).isZero();
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

    private static boolean columnExists(Statement statement, String table, String column)
            throws SQLException {
        ResultSet columns = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = '" + table + "'"
                        + "   AND column_name = '" + column + "'");
        return columns.next();
    }

    private static String scratchDatabase(String label) {
        return "nexachat_mig_v13_" + label + "_" + System.nanoTime();
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
