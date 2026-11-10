package com.nexachat.ops;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Postgres session-level advisory locks for {@code @Scheduled} tasks that must
 * not run concurrently across application instances.
 */
@Component
@Profile("!test")
public class ScheduledAdvisoryLock {

    private final JdbcTemplate jdbcTemplate;

    public ScheduledAdvisoryLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryAcquire(long lockId) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, lockId);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(long lockId) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockId);
    }
}
