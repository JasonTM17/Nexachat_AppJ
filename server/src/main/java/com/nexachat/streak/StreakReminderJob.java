package com.nexachat.streak;

import com.nexachat.notification.DeviceTokenService;
import com.nexachat.notification.FcmGateway;
import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily streak reminder. Once an hour it checks whether the configured local
 * reminder hour has arrived and, if so, sends at most one data push per opted-in
 * user for that user's longest at-risk direct chain they have not written in
 * today.
 *
 * <p>The once-per-day guarantee is an atomic claim: {@code reminder_sent_day}
 * is set with an {@code UPDATE ... WHERE reminder_sent_day IS DISTINCT FROM
 * today} and only the caller whose update changed a row sends. A failed push is
 * therefore not retried the same day, which is the right trade for a nudge.
 *
 * <p>Skipped: muted conversations, blocks in either direction, deleted or
 * suspended accounts, deleted peers, and users without a registered device.
 * The push carries ids and a count only; the client builds the visible text.
 */
@Component
@Profile("!test")
public class StreakReminderJob {

    private static final Logger log = LoggerFactory.getLogger(StreakReminderJob.class);

    private static final long PUSH_TTL_SECONDS = 3 * 3_600L;
    private static final int MAX_PASSES = 1_000;

    /** Best at-risk chain per opted-in user who has not sent today. */
    private static final String CANDIDATES = """
            SELECT DISTINCT ON (side.user_id)
                   side.user_id, cs.conversation_id, peer.display_name AS peer_name, cs.count
            FROM conversation_streaks cs
            CROSS JOIN LATERAL (VALUES
                    (cs.user_low_id, cs.user_high_id, cs.low_day),
                    (cs.user_high_id, cs.user_low_id, cs.high_day)
                ) AS side(user_id, peer_id, my_day)
            JOIN user_streaks us ON us.user_id = side.user_id
                 AND us.reminder_enabled
                 AND us.reminder_sent_day IS DISTINCT FROM CAST(:today AS date)
            JOIN users me ON me.id = side.user_id
                 AND me.deleted_at IS NULL AND me.suspended_at IS NULL
            JOIN users peer ON peer.id = side.peer_id AND peer.deleted_at IS NULL
            JOIN conversation_members cm ON cm.conversation_id = cs.conversation_id
                 AND cm.user_id = side.user_id
            WHERE cs.count >= :minCount
              AND cs.last_day IS NOT NULL
              AND cs.last_day < CAST(:today AS date)
              AND (CAST(:today AS date) - cs.last_day = 1
                   OR (CAST(:today AS date) - cs.last_day = 2
                       AND (cs.freeze_used_on IS NULL
                            OR cs.freeze_used_on < CAST(:today AS date) - 6)))
              AND side.my_day IS DISTINCT FROM CAST(:today AS date)
              AND NOT (cm.muted AND (cm.muted_until IS NULL OR cm.muted_until > :now))
              AND NOT EXISTS (
                    SELECT 1 FROM user_blocks b
                    WHERE (b.blocker_id = side.user_id AND b.blocked_id = side.peer_id)
                       OR (b.blocker_id = side.peer_id AND b.blocked_id = side.user_id))
              AND EXISTS (SELECT 1 FROM user_device_tokens t WHERE t.user_id = side.user_id)
            ORDER BY side.user_id, cs.count DESC, cs.last_day DESC, cs.conversation_id
            LIMIT :batch
            """;

    /** Tokens this user currently owns: a shared device belongs to its newest registrant. */
    private static final String OWNED_TOKENS = """
            SELECT t.token FROM user_device_tokens t
            WHERE t.user_id = ?
              AND NOT EXISTS (
                    SELECT 1 FROM user_device_tokens o
                    WHERE o.token = t.token AND o.user_id <> t.user_id
                      AND o.updated_at > t.updated_at)
            """;

    private record Candidate(UUID userId, UUID conversationId, String peerName, int count) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final StreakProperties properties;
    private final FcmGateway fcmGateway;
    private final DeviceTokenService deviceTokenService;
    private final ScheduledAdvisoryLock advisoryLock;
    private final Clock clock;

    public StreakReminderJob(JdbcTemplate jdbc,
                             StreakProperties properties,
                             FcmGateway fcmGateway,
                             DeviceTokenService deviceTokenService,
                             ScheduledAdvisoryLock advisoryLock,
                             Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.properties = properties;
        this.fcmGateway = fcmGateway;
        this.deviceTokenService = deviceTokenService;
        this.advisoryLock = advisoryLock;
        this.clock = clock;
    }

    @Scheduled(cron = "${nexachat.streaks.reminder-cron:0 0 * * * *}")
    public void scheduledRun() {
        if (!properties.isEnabled() || !properties.isRemindersEnabled()) {
            return;
        }
        advisoryLock.withLock(ScheduledLockIds.STREAK_REMINDER, () -> runAt(clock.instant()));
    }

    /**
     * One reminder pass for the streak day containing {@code now}; does nothing
     * unless {@code now} falls in the configured local reminder hour.
     *
     * @return number of users a push was dispatched for
     */
    int runAt(Instant now) {
        var local = now.atZone(properties.zoneId());
        if (local.getHour() != properties.getReminderHour()) {
            return 0;
        }
        LocalDate today = local.toLocalDate();
        int reminded = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<Candidate> batch = candidates(today, now);
            if (batch.isEmpty()) {
                break;
            }
            for (Candidate candidate : batch) {
                if (claim(candidate.userId(), today) && dispatch(candidate)) {
                    reminded++;
                }
            }
        }
        if (reminded > 0) {
            log.info("Streak reminders sent to {} user(s)", reminded);
        }
        return reminded;
    }

    private List<Candidate> candidates(LocalDate today, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("today", today.toString())
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .addValue("minCount", properties.getReminderMinCount())
                .addValue("batch", properties.getReminderBatchSize());
        return named.query(CANDIDATES, params, (rs, row) -> new Candidate(
                rs.getObject("user_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getString("peer_name"), rs.getInt("count")));
    }

    /** Atomic once-per-day claim; only the caller that flips the day may push. */
    private boolean claim(UUID userId, LocalDate today) {
        return jdbc.update("""
                UPDATE user_streaks SET reminder_sent_day = CAST(? AS date)
                WHERE user_id = ? AND reminder_sent_day IS DISTINCT FROM CAST(? AS date)
                """, today.toString(), userId, today.toString()) == 1;
    }

    private boolean dispatch(Candidate candidate) {
        List<String> tokens = jdbc.queryForList(OWNED_TOKENS, String.class, candidate.userId());
        if (tokens.isEmpty()) {
            return false;
        }
        Map<String, String> data = new HashMap<>();
        data.put("type", "STREAK_REMINDER");
        data.put("recipientId", candidate.userId().toString());
        data.put("conversationId", candidate.conversationId().toString());
        data.put("peerName", candidate.peerName() == null ? "" : candidate.peerName());
        data.put("count", String.valueOf(candidate.count()));
        boolean dispatched = false;
        for (String token : tokens) {
            try {
                fcmGateway.sendDataPush(token, data, "NORMAL", PUSH_TTL_SECONDS)
                        .thenAccept(result -> {
                            if (result != null && result.unregistered()) {
                                removeToken(token);
                            }
                        });
                dispatched = true;
            } catch (RuntimeException failure) {
                log.warn("Streak reminder push failed ({})", failure.getClass().getSimpleName());
            }
        }
        return dispatched;
    }

    private void removeToken(String token) {
        try {
            deviceTokenService.unregisterToken(token);
        } catch (RuntimeException failure) {
            // The message can echo SQL that carries the registration token.
            log.error("Failed to unregister an expired device token: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
