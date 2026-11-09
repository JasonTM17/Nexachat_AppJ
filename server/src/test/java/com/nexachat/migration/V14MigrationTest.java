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
 * V14 applies cleanly to a populated pre-V14 database: the migration only
 * adds the reminder table, so existing users, conversations and members must
 * survive untouched, and every constraint/index must be present afterwards.
 */
class V14MigrationTest {

    @Test
    void v14AddsTheReminderTableWithoutTouchingExistingData() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("v14-reminders")) {
            DataSource dataSource = testDatabase.dataSource();
            // V11 is the latest pre-V14 version on this base; Flyway's target
            // must name an existing migration (V12/V13 are reserved but not
            // present here, so they would apply during the final migrate).
            migrate(dataSource, "11");
            seed(dataSource);

            migrate(dataSource, null);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(tableExists(statement, "conversation_reminders")).isTrue();
                assertThat(columnExists(statement, "anchor_at")).isTrue();
                assertThat(columnExists(statement, "repeat_rule")).isTrue();
                assertThat(columnExists(statement, "fired_at")).isTrue();
                assertThat(columnExists(statement, "claimed_at")).isTrue();
                assertThat(columnExists(statement, "claim_token")).isTrue();
                assertThat(indexExists(statement, "idx_reminders_due")).isTrue();
                assertThat(indexExists(statement, "idx_reminders_conv_active")).isTrue();
                assertThat(indexExists(statement, "idx_reminders_fired")).isTrue();

                assertThat(count(statement, "users")).isEqualTo(2);
                assertThat(count(statement, "conversations")).isEqualTo(1);

                assertThatThrownBy(() -> insertReminder(statement, "YEARLY", "Title", null, null))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertReminder(statement, "NONE", "   ", null, null))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertReminder(statement, "NONE", "x".repeat(201), null, null))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertReminder(
                        statement, "NONE", "Claimed without token", "now()", null))
                        .isInstanceOf(SQLException.class);

                UUID reminderId = insertReminder(statement, "MONTHLY", "Họp nhóm", null, null);
                assertThat(reminderCount(statement)).isEqualTo(1);

                statement.executeUpdate(
                        "UPDATE conversation_reminders SET title = 'Họp nhóm tháng' WHERE id = '"
                                + reminderId + "'");
                assertThat(reminderTitle(statement, reminderId)).isEqualTo("Họp nhóm tháng");

                // Deleting the conversation cascades to its reminders.
                statement.executeUpdate("DELETE FROM conversations");
                assertThat(reminderCount(statement)).isZero();
            }
            validate(dataSource);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static void seed(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users (phone, password_hash, display_name)"
                    + " VALUES ('+84960000001', 'x', 'Một')");
            statement.executeUpdate("INSERT INTO users (phone, password_hash, display_name)"
                    + " VALUES ('+84960000002', 'x', 'Hai')");
            statement.executeUpdate("INSERT INTO conversations (type, direct_key, created_by)"
                    + " SELECT 'DIRECT', 'pair:v14', id FROM users WHERE phone = '+84960000001'");
            statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                    + " SELECT c.id, u.id, 'ADMIN' FROM conversations c, users u"
                    + " WHERE c.direct_key = 'pair:v14' AND u.phone = '+84960000001'");
            statement.executeUpdate("INSERT INTO conversation_members (conversation_id, user_id, role)"
                    + " SELECT c.id, u.id, 'MEMBER' FROM conversations c, users u"
                    + " WHERE c.direct_key = 'pair:v14' AND u.phone = '+84960000002'");
        }
    }

    private static UUID insertReminder(Statement statement, String repeat, String title,
                                       String claimedAt, String claimToken) throws SQLException {
        UUID id = UUID.randomUUID();
        String claimedExpression = claimedAt == null ? "NULL" : claimedAt;
        String tokenExpression = claimToken == null ? "NULL" : "'" + claimToken + "'::uuid";
        statement.executeUpdate("""
                INSERT INTO conversation_reminders
                    (id, conversation_id, created_by, title, remind_at, anchor_at,
                     repeat_rule, claimed_at, claim_token)
                SELECT '%s', c.id, u.id, '%s', now() + interval '1 hour',
                       now() + interval '1 hour', '%s', %s, %s
                FROM conversations c, users u
                WHERE c.direct_key = 'pair:v14' AND u.phone = '+84960000001'
                """.formatted(id, title.replace("'", "''"), repeat,
                claimedExpression, tokenExpression));
        return id;
    }

    private static long reminderCount(Statement statement) throws SQLException {
        return count(statement, "conversation_reminders");
    }

    private static String reminderTitle(Statement statement, UUID id) throws SQLException {
        ResultSet row = statement.executeQuery(
                "SELECT title FROM conversation_reminders WHERE id = '" + id + "'");
        assertThat(row.next()).isTrue();
        return row.getString(1);
    }

    private static long count(Statement statement, String table) throws SQLException {
        ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM " + table);
        row.next();
        return row.getLong(1);
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        ResultSet tables = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name = '" + table + "'");
        return tables.next();
    }

    private static boolean columnExists(Statement statement, String column) throws SQLException {
        ResultSet columns = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = 'conversation_reminders'"
                        + " AND column_name = '" + column + "'");
        return columns.next();
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public'"
                        + " AND tablename = 'conversation_reminders'"
                        + " AND indexname = '" + index + "'");
        return indexes.next();
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

}
