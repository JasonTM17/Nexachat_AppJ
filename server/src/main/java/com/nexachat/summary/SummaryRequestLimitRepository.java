package com.nexachat.summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class SummaryRequestLimitRepository {

    private static final String CONSUME_SQL = """
            INSERT INTO summary_request_limits (user_id, window_type, window_start, request_count)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (user_id, window_type, window_start)
            DO UPDATE SET request_count = summary_request_limits.request_count + 1
            WHERE summary_request_limits.request_count < ?
            RETURNING request_count
            """;

    private final JdbcTemplate jdbcTemplate;

    public SummaryRequestLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryConsume(UUID userId, String windowType, Instant windowStart, int limit) {
        List<Integer> updated = jdbcTemplate.query(
                CONSUME_SQL,
                statement -> {
                    statement.setObject(1, userId);
                    statement.setString(2, windowType);
                    statement.setObject(3, OffsetDateTime.ofInstant(windowStart, ZoneOffset.UTC));
                    statement.setInt(4, limit);
                },
                (resultSet, rowNum) -> resultSet.getInt(1));
        return !updated.isEmpty();
    }
}
