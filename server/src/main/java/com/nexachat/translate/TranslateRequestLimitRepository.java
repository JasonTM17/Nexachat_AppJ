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
}
