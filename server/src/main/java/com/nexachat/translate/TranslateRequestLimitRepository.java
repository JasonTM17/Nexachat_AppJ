package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class TranslateRequestLimitRepository {

    private static final String CONSUME_SQL = """
            INSERT INTO translate_request_limits (user_id, window_type, window_start, request_count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id, window_type, window_start)
            DO UPDATE SET request_count = translate_request_limits.request_count + ?
            WHERE translate_request_limits.request_count + ? <= ?
            RETURNING request_count
            """;

    private final JdbcTemplate jdbcTemplate;

    public TranslateRequestLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Atomically consumes {@code count} tokens; false when the window would exceed {@code limit}. */
    public boolean tryConsume(UUID userId, String windowType, Instant windowStart, int limit, int count) {
        List<Integer> updated = jdbcTemplate.query(
                CONSUME_SQL,
                statement -> {
                    statement.setObject(1, userId);
                    statement.setString(2, windowType);
                    statement.setObject(3, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
                    statement.setInt(4, count);
                    statement.setInt(5, count);
                    statement.setInt(6, count);
                    statement.setInt(7, limit);
                },
                (resultSet, rowNum) -> resultSet.getInt(1));
        return !updated.isEmpty();
    }

    /**
     * Returns tokens taken by an earlier {@link #tryConsume} in the same
     * transaction. Callers use it to make a multi-window refusal spend
     * nothing; the consumed upsert already holds the row lock, so the refund
     * cannot interleave with another consumer. A refund that would take the
     * row to zero deletes it instead: the schema's {@code CHECK
     * (request_count > 0)} rejects 0, and the consume upsert recreates the
     * row on the next request anyway.
     */
    public void refund(UUID userId, String windowType, Instant windowStart, int count) {
        OffsetDateTime windowStartUtc = OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC);
        jdbcTemplate.update("""
                        DELETE FROM translate_request_limits
                        WHERE user_id = ? AND window_type = ? AND window_start = ?
                          AND request_count <= ?
                        """,
                userId, windowType, windowStartUtc, count);
        jdbcTemplate.update("""
                        UPDATE translate_request_limits
                        SET request_count = request_count - ?
                        WHERE user_id = ? AND window_type = ? AND window_start = ?
                        """,
                count, userId, windowType, windowStartUtc);
    }
}
