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
 * Proves the V10 device-token constraint swap applies on the two V7 shapes the
 * guarded migration must tolerate: the shipped V7 draft (single-column
 * {@code uq_device_token}) and an older V7 draft that already carried
 * {@code uq_user_device_token UNIQUE (user_id, token)} without the
 * single-column constraint.
 */
class V10MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v10AppliesOnV7DatabaseWithTheSingleColumnConstraint() throws Exception {
        String database = scratchDatabase("v7");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            migrate(dataSource, "7");
            seedUserAndDeviceToken(dataSource, "token-a");

            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(constraintExists(statement, "user_device_tokens", "uq_user_device_token")).isTrue();
                assertThat(constraintExists(statement, "user_device_tokens", "uq_device_token")).isFalse();
                assertDuplicateDeviceTokenRejected(statement);
                assertIndexGone(statement);
            }
            validate(dataSource);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v10ToleratesAnOlderV7DraftThatAlreadyHasThePerUserConstraint() throws Exception {
        String database = scratchDatabase("v7draft");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            migrate(dataSource, "7");

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                // The older draft already enforces (user_id, token) and never
                // had the single-column constraint V10 would drop.
                statement.executeUpdate("ALTER TABLE user_device_tokens DROP CONSTRAINT uq_device_token");
                statement.executeUpdate("ALTER TABLE user_device_tokens"
                        + " ADD CONSTRAINT uq_user_device_token UNIQUE (user_id, token)");
            }
            seedUserAndDeviceToken(dataSource, "token-a");

            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(constraintExists(statement, "user_device_tokens", "uq_user_device_token")).isTrue();
                assertThat(constraintExists(statement, "user_device_tokens", "uq_device_token")).isFalse();
                assertDuplicateDeviceTokenRejected(statement);
                assertIndexGone(statement);

                assertThat(tableExists(statement, "reaction_events")).isTrue();
                assertThat(tableExists(statement, "request_idempotency")).isTrue();
            }
            validate(dataSource);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v11LeavesPreexistingUsersWithoutATokenCutoff() throws Exception {
        String database = scratchDatabase("v10_user_cutoff");
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            migrate(dataSource, "10");

            UUID userId;
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "INSERT INTO users (phone, password_hash, display_name)"
                                 + " VALUES ('+84900000001', 'x', 'Pre V11 user') RETURNING id")) {
                result.next();
                userId = result.getObject(1, UUID.class);
            }

            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 java.sql.PreparedStatement query = connection.prepareStatement(
                         "SELECT tokens_valid_after FROM users WHERE id = ?")) {
                query.setObject(1, userId);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getTimestamp("tokens_valid_after")).isNull();
                }
            }
            validate(dataSource);
        } finally {
            dropDatabase(database);
        }
    }

    // ----------------------------------------------------------------- helpers

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

    private static void seedUserAndDeviceToken(DataSource dataSource, String token) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (phone, password_hash, display_name)"
                    + " VALUES ('+84900000001', 'x', 'A')");
            statement.executeUpdate("INSERT INTO user_device_tokens (user_id, token, platform)"
                    + " SELECT id, '" + token + "', 'ANDROID' FROM users");
        }
    }

    private static void assertDuplicateDeviceTokenRejected(Statement statement) {
        assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO user_device_tokens (user_id, token, platform)"
                        + " SELECT id, 'token-a', 'ANDROID' FROM users"))
                .isInstanceOf(SQLException.class);
    }

    private static void assertIndexGone(Statement statement) throws SQLException {
        ResultSet index = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public'"
                        + " AND tablename = 'user_device_tokens'"
                        + " AND indexname = 'idx_device_tokens_user'");
        assertThat(index.next()).isFalse();
    }

    private static boolean constraintExists(Statement statement, String table, String constraint)
            throws SQLException {
        ResultSet constraints = statement.executeQuery(
                "SELECT 1 FROM pg_constraint WHERE conrelid = '" + table + "'::regclass"
                        + " AND conname = '" + constraint + "'");
        return constraints.next();
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        ResultSet tables = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name = '" + table + "'");
        return tables.next();
    }

    private static String scratchDatabase(String label) {
        return "nexachat_mig_v10_" + label + "_" + System.nanoTime();
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
