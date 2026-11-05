package com.nexachat.summary;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SummaryCleanupTask {

    private static final String DELETE_EXPIRED_SUMMARIES = """
            WITH expired AS (
                SELECT id FROM message_summaries
                WHERE expires_at <= now()
                ORDER BY expires_at
                LIMIT 1000
            )
            DELETE FROM message_summaries s USING expired e WHERE s.id = e.id
            """;

    private static final String DELETE_OLD_LIMITS = """
            WITH expired AS (
                SELECT user_id, window_type, window_start
                FROM summary_request_limits
                WHERE (window_type = 'MINUTE' AND window_start < now() - INTERVAL '1 day')
                   OR (window_type = 'DAY' AND window_start <
                       ((date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC') - INTERVAL '2 days'))
                ORDER BY window_start
                LIMIT 1000
            )
            DELETE FROM summary_request_limits r USING expired e
            WHERE r.user_id = e.user_id AND r.window_type = e.window_type AND r.window_start = e.window_start
            """;

    private final JdbcTemplate jdbcTemplate;

    public SummaryCleanupTask(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void cleanupExpiredRows() {
        jdbcTemplate.update(DELETE_EXPIRED_SUMMARIES);
        jdbcTemplate.update(DELETE_OLD_LIMITS);
    }
}
