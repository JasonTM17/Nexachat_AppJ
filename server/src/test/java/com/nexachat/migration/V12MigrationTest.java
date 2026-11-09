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

/**
 * Proves the V12 per-member mute/archive columns apply on an empty schema and on
 * a populated V11 schema, that existing membership rows keep their defaults
 * (not muted, not archived) and that the new columns accept a mute expiry.
 *
 * <p>Scratch databases are created and dropped around each test; the shared
 * test database is only used as the maintenance connection. The test uses the
 * same env-JDBC coordinates as the other migration tests.</p>
 */
class V12MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v12AppliesOnFreshDatabase() throws Exception {
        String database = scratchDatabase("fresh");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertColumn(statement, "muted", "boolean", "NO", "false");
                assertColumn(statement, "muted_until", "timestamp with time zone", "YES", null);
                assertColumn(statement, "archived", "boolean", "NO", "false");
            }
            validate(dataSource);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v12AppliesOnV11DatabaseWithDataAndKeepsExistingRowsUnmuted() throws Exception {
        String database = scratchDatabase("v11");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            migrate(dataSource, "11");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (phone, password_hash, display_name)"
                        + " VALUES ('+84900000001', 'x', 'A')");
                statement.executeUpdate("INSERT INTO conversations (type, direct_key, created_by)"
                        + " SELECT 'DIRECT', 'a:b', id FROM users WHERE phone = '+84900000001'");
                statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                        + " SELECT c.id, u.id, 'MEMBER' FROM conversations c, users u"
                        + " WHERE u.phone = '+84900000001'");
            }

            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                ResultSet existing = statement.executeQuery(
                        "SELECT muted, muted_until, archived FROM conversation_members");
                assertThat(existing.next()).isTrue();
                assertThat(existing.getBoolean("muted")).isFalse();
                assertThat(existing.getObject("muted_until")).isNull();
                assertThat(existing.getBoolean("archived")).isFalse();

                // The new columns accept a timed mute and an archive flag.
                statement.executeUpdate(
                        "UPDATE conversation_members"
                                + " SET muted = true, muted_until = now() + interval '1 day'"
                                + " WHERE user_id = (SELECT id FROM users WHERE phone = '+84900000001')");
                ResultSet muted = statement.executeQuery(
                        "SELECT muted, muted_until IS NOT NULL, archived FROM conversation_members");
                assertThat(muted.next()).isTrue();
                assertThat(muted.getBoolean(1)).isTrue();
                assertThat(muted.getBoolean(2)).isTrue();
                assertThat(muted.getBoolean(3)).isFalse();

                statement.executeUpdate(
                        "UPDATE conversation_members SET archived = true");
                ResultSet archived = statement.executeQuery(
                        "SELECT archived FROM conversation_members");
                assertThat(archived.next()).isTrue();
                assertThat(archived.getBoolean(1)).isTrue();
            }

            // V1..V11 were not modified: Flyway validation of the applied
            // checksums against the classpath must succeed.
            validate(dataSource);
        } finally {
            dropDatabase(database);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void assertColumn(Statement statement, String column, String type,
                                     String nullable, String defaultValue) throws SQLException {
        ResultSet columns = statement.executeQuery(
                "SELECT data_type, is_nullable, column_default"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = 'public'"
                        + "   AND table_name = 'conversation_members'"
                        + "   AND column_name = '" + column + "'");
        assertThat(columns.next())
                .as("conversation_members.%s must exist after V12", column).isTrue();
        assertThat(columns.getString("data_type")).isEqualTo(type);
        assertThat(columns.getString("is_nullable")).isEqualTo(nullable);
        if (defaultValue == null) {
            assertThat(columns.getString("column_default")).isNull();
        } else {
            assertThat(columns.getString("column_default")).isEqualTo(defaultValue);
        }
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

    private static void validate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .validate();
    }

    private static String scratchDatabase(String label) {
        return "nexachat_mig_v12_" + label + "_" + System.nanoTime();
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
