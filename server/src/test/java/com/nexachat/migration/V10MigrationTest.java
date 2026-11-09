package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import com.nexachat.testutil.PostgresTestDatabase;

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

    @Test
    void v10AppliesOnV7DatabaseWithTheSingleColumnConstraint() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v10-v7-single-constraint")) {
            DataSource dataSource = testDatabase.dataSource();
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
        }
    }

    @Test
    void v10ToleratesAnOlderV7DraftThatAlreadyHasThePerUserConstraint() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v10-v7-draft")) {
            DataSource dataSource = testDatabase.dataSource();
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
        }
    }

    @Test
    void v11LeavesPreexistingUsersWithoutATokenCutoff() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v11-user-cutoff")) {
            DataSource dataSource = testDatabase.dataSource();
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

}
