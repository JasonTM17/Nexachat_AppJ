package com.nexachat.admin;

import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention keying for {@link AlertRepository#purgeClosed} /
 * {@link AlertRepository#countClosedOlderThan}: the window must run from the
 * row's close time ({@code resolved_at}, falling back to {@code updated_at}
 * when the stamp is missing), never from {@code created_at} — an alert opened
 * long ago but triaged yesterday must not lose its evidence on the next tick.
 * Open rows are never purged regardless of age.
 */
class AlertRepositoryRetentionTest {

    @Test
    void purgeKeysOnCloseTimeNotCreationTime() throws Exception {
        try (PostgresTestDatabase testDatabase = PostgresTestDatabase.create("alert-retention")) {
            DataSource dataSource = testDatabase.dataSource();
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            AlertRepository repository = new AlertRepository(jdbc);
            Fixture fixture = seedFixture(jdbc);

            // Created 40d ago, resolved 1h ago: created_at keying would delete
            // it on the next tick; close-time keying must keep it.
            UUID staleCreateFreshClose = seedAlert(jdbc, fixture, "RESOLVED",
                    "now() - interval '40 days'", "now() - interval '1 hour'",
                    "now() - interval '1 hour'");
            // Created and resolved beyond the window: purged.
            UUID staleCreateStaleClose = seedAlert(jdbc, fixture, "RESOLVED",
                    "now() - interval '40 days'", "now() - interval '31 days'",
                    "now() - interval '31 days'");
            // Closed rows missing the resolved_at stamp fall back to
            // updated_at, so a 40-day-old mutation purges while a 10-day-old
            // one survives — created_at alone would get both wrong.
            UUID dismissedFallbackPurged = seedAlert(jdbc, fixture, "DISMISSED",
                    "now() - interval '50 days'", "now() - interval '40 days'", "NULL");
            UUID resolvedFallbackKept = seedAlert(jdbc, fixture, "RESOLVED",
                    "now() - interval '50 days'", "now() - interval '10 days'", "NULL");
            // Status guard: an ancient OPEN row is out of scope entirely.
            UUID ancientOpen = seedAlert(jdbc, fixture, "OPEN",
                    "now() - interval '100 days'", "now() - interval '100 days'", "NULL");

            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

            assertThat(repository.countClosedOlderThan(cutoff)).isEqualTo(2);
            assertThat(repository.purgeClosed(cutoff, 100)).isEqualTo(2);
            assertThat(repository.countClosedOlderThan(cutoff)).isZero();

            List<UUID> survivors = jdbc.queryForList(
                    "SELECT id FROM message_alerts", UUID.class);
            assertThat(survivors).containsExactlyInAnyOrder(
                    staleCreateFreshClose, resolvedFallbackKept, ancientOpen);
            assertThat(survivors).doesNotContain(staleCreateStaleClose, dismissedFallbackPurged);
        }
    }

    private record Fixture(UUID userId, UUID conversationId) {
    }

    /** One user + group conversation behind the alert FK chain. */
    private static Fixture seedFixture(JdbcTemplate jdbc) {
        UUID userId = jdbc.queryForObject("""
                INSERT INTO users (phone, password_hash, display_name)
                VALUES ('+84900000001', 'x', 'Retention probe') RETURNING id
                """, UUID.class);
        UUID conversationId = jdbc.queryForObject("""
                INSERT INTO conversations (type, created_by)
                VALUES ('GROUP', ?) RETURNING id
                """, UUID.class, userId);
        return new Fixture(userId, conversationId);
    }

    /**
     * Seeds one alert with explicit timestamp expressions (constant SQL
     * fragments like {@code now() - interval '40 days'} or {@code NULL}).
     * A fresh message per alert satisfies UNIQUE(message_id, category).
     */
    private static UUID seedAlert(JdbcTemplate jdbc, Fixture fixture, String status,
                                  String createdAt, String updatedAt, String resolvedAt) {
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)
                VALUES (?, ?, ?, 'TEXT', 'probe') RETURNING id
                """, UUID.class, fixture.conversationId(), fixture.userId(), UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO message_alerts
                    (message_id, conversation_id, sender_id, message_seq, category, severity,
                     matched_rule_ids, status, created_at, updated_at, resolved_at)
                SELECT ?, ?, ?, 1, 'CREDENTIAL_SHARING', 'HIGH',
                       '{}'::uuid[], '%s', %s, %s, %s
                RETURNING id
                """.formatted(status, createdAt, updatedAt, resolvedAt),
                UUID.class, messageId, fixture.conversationId(), fixture.userId());
    }
}
