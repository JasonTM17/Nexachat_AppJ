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

/** Proves V18 applies to populated V16 data and preserves ownership/cascade constraints. */
class V18MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v18AppliesToPopulatedV16DatabaseAndEnforcesLabelOwnershipAndCascades() throws Exception {
        String database = "nexachat_mig_v18_" + System.nanoTime();
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
                        + " VALUES ('" + alice + "', '+84980000001', 'x', 'Alice')");
                statement.executeUpdate("INSERT INTO users (id, phone, password_hash, display_name)"
                        + " VALUES ('" + bob + "', '+84980000002', 'x', 'Bob')");
                statement.executeUpdate("INSERT INTO conversations (id, type, title, created_by)"
                        + " VALUES ('" + conversation + "', 'GROUP', 'Labels', '" + alice + "')");
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
                assertThat(tableExists(statement, "conversation_labels")).isTrue();
                assertThat(tableExists(statement, "conversation_label_assignments")).isTrue();
                assertThat(indexExists(statement,
                        "uq_conversation_labels_user_name_lower")).isTrue();
                assertThat(indexExists(statement,
                        "idx_conversation_label_assignments_user_label_conversation")).isTrue();
                assertThat(count(statement, "users")).isEqualTo(2);
                assertThat(count(statement, "conversations")).isEqualTo(1);
                assertThat(count(statement, "conversation_members")).isEqualTo(2);
                assertThat(count(statement, "messages")).isEqualTo(1);

                UUID aliceLabel = UUID.randomUUID();
                UUID bobLabel = UUID.randomUUID();
                insertLabel(statement, aliceLabel, alice, "Urgent", "ROSE");
                insertLabel(statement, bobLabel, bob, "Private", "TEAL");
                insertAssignment(statement, conversation, alice, aliceLabel);
                insertAssignment(statement, conversation, bob, bobLabel);

                assertThatThrownBy(() -> insertLabel(statement,
                        UUID.randomUUID(), alice, "urgent", "BLUE"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertLabel(statement,
                        UUID.randomUUID(), alice, "Invalid color", "NOT_A_COLOR"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertAssignment(statement,
                        conversation, alice, bobLabel))
                        .isInstanceOf(SQLException.class);

                statement.executeUpdate("DELETE FROM conversation_members WHERE conversation_id = '"
                        + conversation + "' AND user_id = '" + bob + "'");
                assertThat(count(statement, "conversation_label_assignments")).isEqualTo(1);
                statement.executeUpdate("DELETE FROM conversation_labels WHERE id = '" + aliceLabel + "'");
                assertThat(count(statement, "conversation_label_assignments")).isZero();
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

    private static void insertLabel(Statement statement, UUID id, UUID userId,
                                    String name, String colorId) throws SQLException {
        statement.executeUpdate("INSERT INTO conversation_labels (id, user_id, name, color_id)"
                + " VALUES ('" + id + "', '" + userId + "', '" + name + "', '" + colorId + "')");
    }

    private static void insertAssignment(Statement statement, UUID conversationId,
                                         UUID userId, UUID labelId) throws SQLException {
        statement.executeUpdate("INSERT INTO conversation_label_assignments"
                + " (conversation_id, user_id, label_id) VALUES ('" + conversationId + "', '"
                + userId + "', '" + labelId + "')");
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        ResultSet result = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name = '" + table + "'");
        return result.next();
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet result = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return result.next();
    }

    private static long count(Statement statement, String table) throws SQLException {
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table);
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
