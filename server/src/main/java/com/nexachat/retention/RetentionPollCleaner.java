package com.nexachat.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Converts aged ended polls into compact change-feed tombstones. */
@Component
@Profile("!test")
class RetentionPollCleaner {

    private static final String END_AT = """
            LEAST(
                COALESCE(p.closed_at, 'infinity'::timestamptz),
                COALESCE(p.closes_at, 'infinity'::timestamptz),
                COALESCE(p.deleted_at, 'infinity'::timestamptz)
            )
            """;

    private static final String PAYLOAD_REMAINS = """
            (p.deleted_at IS NULL OR p.question <> '[retained]'
             OR EXISTS (SELECT 1 FROM poll_options o WHERE o.poll_id = p.id)
             OR EXISTS (SELECT 1 FROM poll_votes v WHERE v.poll_id = p.id))
            """;

    private static final String SELECT_DRY_RUN = """
            SELECT p.id, p.deleted_at
            FROM polls p
            WHERE %s <= ? AND %s
            ORDER BY %s, p.id
            LIMIT ?
            """.formatted(END_AT, PAYLOAD_REMAINS, END_AT);

    private static final String SELECT_LOCKED = """
            WITH eligible_conversations AS MATERIALIZED (
                SELECT c.id
                FROM conversations c
                WHERE EXISTS (
                    SELECT 1 FROM polls p
                    WHERE p.conversation_id = c.id
                      AND %s <= ?
                      AND %s
                )
                ORDER BY c.id
                LIMIT ?
                FOR UPDATE OF c SKIP LOCKED
            ), eligible_polls AS MATERIALIZED (
                SELECT p.id, p.deleted_at
                FROM polls p
                JOIN eligible_conversations c ON c.id = p.conversation_id
                WHERE %s <= ? AND %s
                ORDER BY %s, p.id
                LIMIT ?
                FOR UPDATE OF p SKIP LOCKED
            )
            SELECT id, deleted_at FROM eligible_polls
            """.formatted(END_AT, PAYLOAD_REMAINS, END_AT, PAYLOAD_REMAINS, END_AT);

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final RetentionProperties properties;

    RetentionPollCleaner(JdbcTemplate jdbcTemplate,
                         NamedParameterJdbcTemplate namedJdbc,
                         RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.properties = properties;
    }

    RetentionJobRunner.JobRunResult run(boolean dryRun) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(properties.getJobs().getPolls().getRetentionDays());
        int batchSize = properties.getBatchSize();

        if (dryRun) {
            List<PollRow> rows = jdbcTemplate.query(SELECT_DRY_RUN,
                    (rs, rowNum) -> new PollRow(rs.getObject("id", UUID.class)), cutoff, batchSize);
            return new RetentionJobRunner.JobRunResult(rows.size(), 0, 0);
        }

        List<PollRow> rows = jdbcTemplate.query(SELECT_LOCKED,
                (rs, rowNum) -> new PollRow(rs.getObject("id", UUID.class)),
                cutoff, batchSize, cutoff, batchSize);
        if (rows.isEmpty()) {
            return new RetentionJobRunner.JobRunResult(0, 0, 0);
        }
        List<UUID> ids = rows.stream().map(PollRow::id).toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("batch", batchSize);

        int tombstones = namedJdbc.update("""
                UPDATE polls p
                SET question = '[retained]',
                    deleted_at = COALESCE(p.deleted_at, clock_timestamp()),
                    version = CASE WHEN p.deleted_at IS NULL THEN nextval('poll_version_seq') ELSE p.version END
                WHERE p.id IN (:ids)
                  AND (p.deleted_at IS NULL OR p.question <> '[retained]')
                """, params);

        Integer votes = namedJdbc.queryForObject("""
                WITH candidates AS MATERIALIZED (
                    SELECT v.ctid
                    FROM poll_votes v
                    WHERE v.poll_id IN (:ids)
                    ORDER BY v.poll_id, v.option_id, v.user_id
                    LIMIT :batch
                    FOR UPDATE OF v SKIP LOCKED
                ), deleted AS (
                    DELETE FROM poll_votes v USING candidates c
                    WHERE v.ctid = c.ctid
                    RETURNING 1
                )
                SELECT count(*) FROM deleted
                """, params, Integer.class);

        Integer options = namedJdbc.queryForObject("""
                WITH candidates AS MATERIALIZED (
                    SELECT o.ctid
                    FROM poll_options o
                    WHERE o.poll_id IN (:ids)
                      AND NOT EXISTS (SELECT 1 FROM poll_votes v WHERE v.option_id = o.id)
                    ORDER BY o.poll_id, o.position
                    LIMIT :batch
                    FOR UPDATE OF o SKIP LOCKED
                ), deleted AS (
                    DELETE FROM poll_options o USING candidates c
                    WHERE o.ctid = c.ctid
                    RETURNING 1
                )
                SELECT count(*) FROM deleted
                """, params, Integer.class);

        return new RetentionJobRunner.JobRunResult(
                tombstones + (votes == null ? 0 : votes) + (options == null ? 0 : options), 0, 0);
    }

    private record PollRow(UUID id) {
    }
}
