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
 * Proves the RAG migration applies on an empty schema and on a populated V8
 * schema, and that the embedding primary key cascades with its message.
 */
class V9MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v9AppliesOnFreshDatabase() throws Exception {
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
                assertThat(columnExists(statement, "message_embeddings", "message_id")).isTrue();
                assertThat(columnExists(statement, "message_embeddings", "conversation_id")).isTrue();
                assertThat(columnExists(statement, "message_embeddings", "model")).isTrue();
                assertThat(columnExists(statement, "message_embeddings", "dim")).isTrue();
                assertThat(columnExists(statement, "message_embeddings", "vector")).isTrue();
                assertThat(columnExists(statement, "message_embeddings", "content_hash")).isTrue();
                assertThat(columnExists(statement, "rag_ask_cache", "question_vector")).isTrue();
                assertThat(columnExists(statement, "rag_ask_request_limits", "window_type")).isTrue();

                ResultSet index = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                                + " WHERE schemaname = 'public'"
                                + "   AND tablename = 'message_embeddings'"
                                + "   AND indexname = 'idx_message_embeddings_conversation'");
                assertThat(index.next()).isTrue();
                assertThat(index.getString(1)).contains("conversation_id");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v9AppliesOnV8DatabaseWithDataAndCascadesWithMessages() throws Exception {
        String database = scratchDatabase("v8");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("8")
                    .load()
                    .migrate();

            UUID userId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            UUID messageId = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + userId + "', '+84900000001', 'x', 'A')");
                statement.executeUpdate("INSERT INTO conversations (id, type, direct_key, created_by)"
                        + " VALUES ('" + conversationId + "', 'DIRECT', 'a:b', '" + userId + "')");
                statement.executeUpdate("INSERT INTO messages (id, conversation_id, sender_id,"
                        + " client_message_id, type, content) VALUES ('" + messageId + "', '"
                        + conversationId + "', '" + userId + "', gen_random_uuid(), 'TEXT', 'Chào bạn')");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO message_embeddings"
                                + " (message_id, conversation_id, model, dim, vector, content_hash)"
                                + " VALUES ('" + messageId + "', '" + conversationId + "', 'lexical', 256,"
                                + " decode(repeat('00', 1024), 'hex'), '" + "a".repeat(64) + "')");
                ResultSet count = statement.executeQuery(
                        "SELECT count(*) FROM message_embeddings WHERE message_id = '" + messageId + "'");
                count.next();
                assertThat(count.getInt(1)).isEqualTo(1);

                statement.executeUpdate("DELETE FROM messages WHERE id = '" + messageId + "'");
                ResultSet afterDelete = statement.executeQuery(
                        "SELECT count(*) FROM message_embeddings WHERE message_id = '" + messageId + "'");
                afterDelete.next();
                assertThat(afterDelete.getInt(1)).isEqualTo(0);

                assertThatThrownBy(() -> statement.executeUpdate(
                        "INSERT INTO message_embeddings"
                                + " (message_id, conversation_id, model, dim, vector, content_hash)"
                                + " VALUES (gen_random_uuid(), '" + conversationId + "', 'lexical', 256,"
                                + " decode(repeat('00', 1024), 'hex'), '" + "a".repeat(64) + "')"))
                        .isInstanceOf(SQLException.class);
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
        return "nexachat_mig_v9_" + label + "_" + System.nanoTime();
    }

    private static DataSource dataSource(String database) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(databaseUrl(database));
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        return dataSource;
    }

    private static String serverUrl() {
        String url = JDBC_URL.split("\\?", 2)[0];
        return url.substring(0, url.lastIndexOf('/') + 1);
    }

    private static String databaseUrl(String database) {
        int query = JDBC_URL.indexOf('?');
        String options = query < 0 ? "" : JDBC_URL.substring(query);
        return serverUrl() + database + options;
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
        return driver.connect(databaseUrl("postgres"), properties);
    }
}
