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
 * Proves V20 applies to a populated V16 database without touching existing
 * rows, adds the group invite links, join requests, and invite ban tables,
 * and enforces the active link uniqueness and review/revocation constraints.
 *
 * <p>The scratch database lives beside the shared test database and is dropped
 * afterwards; the shared database is only used as the maintenance connection.
 */
class V20MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v20AppliesOnPopulatedV16DatabaseAndEnforcesInviteLinkInvariants() throws Exception {
        String database = "nexachat_mig_v20_" + System.nanoTime();
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
            UUID message = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + alice + "', '+84950000001', 'x', 'Alice')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + bob + "', '+84950000002', 'x', 'Bob')");
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
                assertThat(tableExists(statement, "group_invite_links")).isTrue();
                assertThat(tableExists(statement, "group_join_requests")).isTrue();
                assertThat(tableExists(statement, "group_invite_bans")).isTrue();

                assertThat(indexExists(statement, "idx_group_invite_links_one_active")).isTrue();
                assertThat(indexExists(statement, "idx_group_invite_links_token_hash")).isTrue();
                assertThat(indexExists(statement, "idx_group_join_requests_user_conv_pending")).isTrue();
                assertThat(indexExists(statement, "idx_group_join_requests_conv_status")).isTrue();
                assertThat(indexExists(statement, "idx_group_invite_bans_lookup")).isTrue();

                // Existing rows survive the migration untouched
                assertThat(countWhere(statement, "SELECT COUNT(*) FROM users")).isEqualTo(2L);
                assertThat(countWhere(statement, "SELECT COUNT(*) FROM conversations")).isEqualTo(1L);
                assertThat(countWhere(statement, "SELECT COUNT(*) FROM messages")).isEqualTo(1L);

                // Insert active invite link
                UUID linkId = UUID.randomUUID();
                statement.executeUpdate("INSERT INTO group_invite_links (id, conversation_id, created_by,"
                        + " token_hash, requires_approval, max_uses, use_count)"
                        + " VALUES ('" + linkId + "', '" + conversation + "', '" + alice + "',"
                        + " 'a1b2c3d4e5f67890123456789012345678901234567890123456789012345678', false, 10, 0)");

                // Second active invite link on same conversation fails due to unique partial index
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO group_invite_links (id, conversation_id, created_by,"
                        + " token_hash, requires_approval)"
                        + " VALUES (gen_random_uuid(), '" + conversation + "', '" + alice + "',"
                        + " 'b1b2c3d4e5f67890123456789012345678901234567890123456789012345678', false)"))
                        .isInstanceOf(SQLException.class);

                // Revocation consistency constraint: revoked_at without revoked_by fails
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE group_invite_links"
                        + " SET revoked_at = now(), revoked_by = NULL WHERE id = '" + linkId + "'"))
                        .isInstanceOf(SQLException.class);

                // Proper revocation succeeds
                statement.executeUpdate("UPDATE group_invite_links"
                        + " SET revoked_at = now(), revoked_by = '" + alice + "' WHERE id = '" + linkId + "'");

                // Now another active link can be created
                UUID linkId2 = UUID.randomUUID();
                statement.executeUpdate("INSERT INTO group_invite_links (id, conversation_id, created_by,"
                        + " token_hash, requires_approval)"
                        + " VALUES ('" + linkId2 + "', '" + conversation + "', '" + alice + "',"
                        + " 'c1b2c3d4e5f67890123456789012345678901234567890123456789012345678', true)");

                // Join request with pending status
                UUID reqId = UUID.randomUUID();
                statement.executeUpdate("INSERT INTO group_join_requests (id, conversation_id, invite_link_id, user_id, status)"
                        + " VALUES ('" + reqId + "', '" + conversation + "', '" + linkId2 + "', '" + bob + "', 'PENDING')");

                // Second pending request for same user on same conversation fails
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO group_join_requests (id, conversation_id, invite_link_id, user_id, status)"
                        + " VALUES (gen_random_uuid(), '" + conversation + "', '" + linkId2 + "', '" + bob + "', 'PENDING')"))
                        .isInstanceOf(SQLException.class);

                // Review consistency: non-pending without reviewer fails
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE group_join_requests"
                        + " SET status = 'APPROVED', reviewed_at = now(), reviewed_by = NULL WHERE id = '" + reqId + "'"))
                        .isInstanceOf(SQLException.class);

                // Proper review succeeds
                statement.executeUpdate("UPDATE group_join_requests"
                        + " SET status = 'APPROVED', reviewed_at = now(), reviewed_by = '" + alice + "' WHERE id = '" + reqId + "'");
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
        ResultSet tables = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '"
                        + table + "'");
        return tables.next();
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static long countWhere(Statement statement, String sql) throws SQLException {
        ResultSet result = statement.executeQuery(sql);
        result.next();
        return result.getLong(1);
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
