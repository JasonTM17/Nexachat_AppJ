package com.nexachat.admin;

import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminOverviewService} day bucketing is pinned to UTC. With the DB
 * session timezone set to UTC+7, {@code created_at::date} and
 * {@code CURRENT_DATE} resolve a day ahead of the UTC window the service
 * computes; bucketing must use {@code AT TIME ZONE 'UTC'} throughout or the
 * first/last series points and the "today" KPIs shift by up to the offset.
 */
class AdminOverviewServiceTimezoneTest {

    @Test
    void seriesAndTodayKpisBucketInUtcUnderANonUtcSessionTimezone() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("overview-tz")) {
            DataSource dataSource = testDatabase.dataSource();
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            // Every new session on this database resolves ::date, CURRENT_DATE
            // and date_trunc in UTC+7 — the drift the fix must neutralize.
            jdbc.execute("ALTER DATABASE \"" + testDatabase.databaseName()
                    + "\" SET TimeZone = 'Asia/Ho_Chi_Minh'");

            seed(jdbc);

            AdminOverviewService service = new AdminOverviewService(
                    jdbc, null, null, null, new AdminProperties(null, null, null),
                    "nexachat-it-nonexistent-uploads-root");
            AdminOverviewService.Overview overview = service.overview(7);

            String utcToday = LocalDate.now(ZoneOffset.UTC).toString();
            List<AdminOverviewService.SeriesPoint> series = overview.series();
            assertThat(series).hasSize(7);
            AdminOverviewService.SeriesPoint last = series.get(series.size() - 1);
            assertThat(last.date()).isEqualTo(utcToday);
            // The user/message created at today 23:30 UTC belongs to the UTC
            // "today" bucket; session-timezone bucketing files it as tomorrow
            // (outside the generated range) or under the wrong date label.
            assertThat(last.registrations()).isEqualTo(1);
            assertThat(last.messages()).isEqualTo(1);

            // The user created at yesterday 22:00 UTC sits inside local +7
            // "today" (its midnight is yesterday 17:00 UTC) but outside the
            // UTC day — only the UTC-bounded count may see just the new row.
            assertThat(overview.kpis().registrationsToday()).isEqualTo(1);
            assertThat(overview.kpis().messagesToday()).isEqualTo(1);
        }
    }

    private static void seed(JdbcTemplate jdbc) {
        // Both seeds pin exact UTC instants: yesterday 22:00 UTC (inside the
        // +7 session's "today", outside the UTC one) and today 23:30 UTC (the
        // UTC-today bucket the +7 session files as tomorrow).
        UUID oldUser = jdbc.queryForObject("""
                INSERT INTO users (phone, password_hash, display_name, created_at)
                VALUES ('+84900000011', 'x', 'Yesterday boundary',
                        ((now() AT TIME ZONE 'UTC')::date AT TIME ZONE 'UTC')
                            - interval '2 hours')
                RETURNING id
                """, UUID.class);
        UUID newUser = jdbc.queryForObject("""
                INSERT INTO users (phone, password_hash, display_name, created_at)
                VALUES ('+84900000012', 'x', 'Late UTC day',
                        ((now() AT TIME ZONE 'UTC')::date + interval '23 hours 30 minutes')
                            AT TIME ZONE 'UTC')
                RETURNING id
                """, UUID.class);
        UUID conversationId = jdbc.queryForObject("""
                INSERT INTO conversations (type, created_by)
                VALUES ('GROUP', ?) RETURNING id
                """, UUID.class, newUser);
        jdbc.update("""
                INSERT INTO messages (conversation_id, sender_id, client_message_id,
                                      type, content, created_at)
                VALUES (?, ?, ?, 'TEXT', 'late utc message',
                        ((now() AT TIME ZONE 'UTC')::date + interval '23 hours 30 minutes')
                            AT TIME ZONE 'UTC')
                """, conversationId, newUser, UUID.randomUUID());
    }
}
