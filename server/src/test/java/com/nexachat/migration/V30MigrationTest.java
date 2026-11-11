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

/**
 * V30 replaces the live-only refresh-token owner index with a full
 * {@code (user_id, id)} index. Proves the migration applies on a populated
 * schema, keeps the token rows, and that all three user-scoped statements can
 * use the new index. A full table scan is disabled only to make the plan
 * deterministic on the tiny fixture; the production-scale evidence is in
 * {@code reports/r4-329-d5.md}.
 */
class V30MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v30ReplacesThePartialOwnerIndexAndServesUserScopedTokenQueries() throws Exception {
        String database = "nexachat_mig_v30_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                insertUser(statement, alice, "+84931000001", "Alice");
                insertUser(statement, bob, "+84931000002", "Bob");

                // Alice: 3 live sessions and 2 revoked rotation predecessors
                // carrying replay payloads.
                for (int i = 0; i < 3; i++) {
                    statement.executeUpdate(liveToken(alice, "a-live-" + i));
                }
                for (int i = 0; i < 2; i++) {
                    statement.executeUpdate(replayToken(alice, "a-replay-" + i));
                }
                // Bob keeps one token of each kind.
                statement.executeUpdate(liveToken(bob, "b-live-0"));
                statement.executeUpdate(replayToken(bob, "b-replay-0"));

                assertThat(indexExists(statement, "idx_refresh_user")).isFalse();
                assertThat(indexExists(statement, "idx_refresh_user_id")).isTrue();
                assertThat(scalar(statement, "SELECT count(*) FROM refresh_tokens")).isEqualTo(7);
                assertThat(scalar(statement,
                        "SELECT count(*) FROM refresh_tokens WHERE user_id = '" + alice + "'"))
                        .isEqualTo(5);

                statement.executeUpdate("ANALYZE refresh_tokens");
                statement.executeUpdate("SET enable_seqscan = off");

                assertThat(planOf(statement,
                        "SELECT * FROM refresh_tokens WHERE user_id = '" + alice
                                + "' ORDER BY id FOR UPDATE"))
                        .contains("idx_refresh_user_id");
                assertThat(planOf(statement,
                        "UPDATE refresh_tokens SET revoked_at = now()"
                                + " WHERE user_id = '" + alice + "' AND revoked_at IS NULL"))
                        .contains("idx_refresh_user_id");
                assertThat(planOf(statement,
                        "UPDATE refresh_tokens SET replay_ciphertext = NULL, replay_nonce = NULL,"
                                + " replay_invalidated = TRUE"
                                + " WHERE user_id = '" + alice + "' AND replay_key_digest IS NOT NULL"))
                        .contains("idx_refresh_user_id");
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

    private static void insertUser(Statement statement, UUID id, String phone, String name)
            throws SQLException {
        statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                + " VALUES ('" + id + "', '" + phone + "', 'x', '" + name + "')");
    }

    private static String liveToken(UUID userId, String seed) {
        return "INSERT INTO refresh_tokens (user_id, token_hash, created_at, expires_at)"
                + " VALUES ('" + userId + "', '" + hash(seed) + "', now(), now() + interval '30 days')";
    }

    private static String replayToken(UUID userId, String seed) {
        return "INSERT INTO refresh_tokens (user_id, token_hash, created_at, expires_at, revoked_at,"
                + " replaced_by, replay_key_digest, replay_ciphertext, replay_nonce, replay_rotated_at,"
                + " replay_until, replay_invalidated)"
                + " VALUES ('" + userId + "', '" + hash(seed) + "', now(), now() + interval '30 days',"
                + " now(), gen_random_uuid(), '" + hash(seed + "-digest") + "', '\\x00010203',"
                + " '\\x000102030405060708090a0b', now(), now() + interval '15 minutes', FALSE)";
    }

    private static String hash(String seed) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
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
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
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
