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
 * Proves the per-conversation AI opt-out migration applies on an empty schema
 * and on a populated V11 schema, and that both foreign keys cascade.
 */
class V13MigrationTest {

    @Test
    void v13AppliesOnFreshDatabase() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v13-fresh")) {
            DataSource dataSource = testDatabase.dataSource();
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
        }
    }

    @Test
    void v13AppliesOnV11DatabaseWithDataAndCascades() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v13-v11-data")) {
            DataSource dataSource = testDatabase.dataSource();
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

}
