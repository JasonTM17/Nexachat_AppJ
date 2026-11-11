package com.nexachat.ops;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

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

    /**
     * Runs {@code action} while holding {@code lockId} on one physical
     * connection, so the session-level lock is always released on the session
     * that owns it. Returns {@code null} without running the action when
     * another instance holds the lock.
     *
     * <p><b>Pool note:</b> the lock connection stays checked out for the
     * duration of {@code action}; the data source needs at least two
     * connections when the action itself queries the database. Callers that
     * need to read the database must use
     * {@link #withLock(long, ConnectionCallback)} and run those statements on
     * the supplied connection instead: otherwise a holder of the last free
     * connection that waits for this lock can starve the action's own query
     * (a pool-level deadlock).
     *
     * @param lockId stable advisory lock id
     * @param action work to run under the lock
     * @param <T>    result type; the action must not return {@code null}
     * @return the action result, or {@code null} when the lock was not acquired
     */
    public <T> T withLock(long lockId, Supplier<T> action) {
        return withLock(lockId, connection -> action.get());
    }

    /**
     * Connection-aware variant of {@link #withLock(long, Supplier)}. The
     * callback's statements run on the lock-owning connection, so the action
     * never waits for a second pooled connection while other sessions wait for
     * the advisory lock.
     *
     * @param lockId stable advisory lock id
     * @param action work to run under the lock; receives the owning connection
     * @param <T>    result type; the action must not return {@code null}
     * @return the action result, or {@code null} when the lock was not acquired
     */
    public <T> T withLock(long lockId, ConnectionCallback<T> action) {
        return jdbcTemplate.execute((ConnectionCallback<T>) connection -> {
            if (!tryAcquire(connection, lockId)) {
                return null;
            }
            try {
                return action.doInConnection(connection);
            } finally {
                release(connection, lockId);
            }
        });
    }

    private static boolean tryAcquire(Connection connection, long lockId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, lockId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void release(Connection connection, long lockId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, lockId);
            try (ResultSet ignored = statement.executeQuery()) {
                // The boolean result only reports whether this session owned the lock.
            }
        }
    }
}
