package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Applies the Flyway migration chain to throwaway PostgreSQL databases so the
 * V8 upgrade is proven on both an empty schema and a populated V7 schema.
 *
 * <p>Scratch databases are created and dropped around each test; the shared
 * test database is only used as the maintenance connection. The test uses the
 * same env-JDBC coordinates as the {@code it} profile.</p>
 */
class V8MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v8AppliesOnFreshDatabase() throws Exception {
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
                ResultSet columns = statement.executeQuery(
                        "SELECT data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = 'public'"
                        + "   AND table_name = 'message_attachments'"
                        + "   AND column_name = 'blur_hash'");
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString("data_type")).isEqualTo("character varying");
                assertThat(columns.getInt("character_maximum_length")).isEqualTo(64);
                assertThat(columns.getString("is_nullable")).isEqualTo("YES");

                boolean lengthCheck = false;
                ResultSet constraints = statement.executeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                        + " WHERE conrelid = 'public.message_attachments'::regclass"
                        + "   AND contype = 'c'");
                while (constraints.next()) {
                    if (constraints.getString(1).contains("blur_hash")) {
                        lengthCheck = true;
                    }
                }
                assertThat(lengthCheck).isTrue();

                ResultSet function = statement.executeQuery(
                        "SELECT provolatile FROM pg_proc"
                        + " WHERE proname = 'f_unaccent' AND pronamespace = 'public'::regnamespace");
                assertThat(function.next()).isTrue();
                assertThat(function.getString("provolatile")).isEqualTo("i");

                ResultSet index = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                        + " WHERE schemaname = 'public' AND tablename = 'messages'"
                        + "   AND indexname = 'idx_msg_conv_content_trgm'");
                assertThat(index.next()).isTrue();
                assertThat(index.getString("indexdef")).contains("gin_trgm_ops");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v8AppliesOnV7DatabaseWithData() throws Exception {
        String database = scratchDatabase("v7");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target("7")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO users (phone, password_hash, display_name)"
                        + " VALUES ('+84900000001', 'x', 'A')");
                statement.executeUpdate(
                        "INSERT INTO conversations (type, direct_key, created_by)"
                        + " SELECT 'DIRECT', 'a:b', id FROM users WHERE phone = '+84900000001'");
                statement.executeUpdate(
                        "INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)"
                        + " SELECT c.id, u.id, gen_random_uuid(), 'TEXT', 'Chào bạn'"
                        + " FROM conversations c, users u WHERE u.phone = '+84900000001'");
                statement.executeUpdate(
                        "INSERT INTO message_attachments"
                        + " (message_id, owner_id, storage_key, original_name, mime_type, size_bytes)"
                        + " SELECT m.id, m.sender_id, 'attachments/old.png', 'old.png', 'image/png', 10"
                        + " FROM messages m");
            }

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                ResultSet messages = statement.executeQuery("SELECT count(*) FROM messages");
                messages.next();
                assertThat(messages.getInt(1)).isEqualTo(1);

                ResultSet attachments = statement.executeQuery(
                        "SELECT blur_hash FROM message_attachments");
                assertThat(attachments.next()).isTrue();
                assertThat(attachments.getString(1)).isNull();

                ResultSet index = statement.executeQuery(
                        "SELECT indexdef FROM pg_indexes"
                        + " WHERE schemaname = 'public' AND indexname = 'idx_msg_conv_content_trgm'");
                assertThat(index.next()).isTrue();

                try {
                    statement.executeUpdate(
                            "UPDATE message_attachments SET blur_hash = 'short'");
                    fail("expected the blur_hash length check to reject the update");
                } catch (SQLException expected) {
                    assertThat(expected.getMessage()).contains("blur_hash_length");
                }
            }

            // V1..V7 were not modified: Flyway validation of the applied
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

    private static String scratchDatabase(String label) {
        return "nexachat_mig_" + label + "_" + System.nanoTime();
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
