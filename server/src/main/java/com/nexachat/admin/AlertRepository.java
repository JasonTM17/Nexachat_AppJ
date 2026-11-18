package com.nexachat.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code message_alerts} and {@code alert_notes} access. One alert per
 * (message, category); the UNIQUE constraint is the dedup contract — a
 * replayed send event can never produce a second row.
 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AlertRow(UUID id, UUID messageId, UUID conversationId, UUID senderId,
                           long messageSeq, String category, String severity,
                           List<UUID> matchedRuleIds, String snippet, boolean snippetTombstoned,
                           String status, UUID assigneeId, Instant assignedAt,
                           UUID resolvedBy, Instant resolvedAt, boolean escalated,
                           boolean dryRun, Instant createdAt, Instant updatedAt) {
    }

    public record NoteRow(long id, UUID alertId, UUID authorId, String body, Instant createdAt) {
    }

    /**
     * Inserts one alert for a category match. {@code rescan=false} (fresh
     * send) uses {@code DO NOTHING} so a replayed event is a strict no-op;
     * {@code rescan=true} (edited message) refreshes the stored match data
     * and re-opens a previously closed alert, since the content changed.
     */
    public void insertAlert(UUID messageId, UUID conversationId, UUID senderId, long seq,
                            String category, String severity, List<UUID> ruleIds,
                            String snippet, boolean dryRun, boolean rescan) {
        String ruleIdArray = ruleIds.stream().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        if (rescan) {
            jdbcTemplate.update("""
                    INSERT INTO message_alerts
                        (message_id, conversation_id, sender_id, message_seq, category, severity,
                         matched_rule_ids, snippet, status, dry_run)
                    VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?, 'OPEN', ?)
                    ON CONFLICT (message_id, category) DO UPDATE SET
                        severity = EXCLUDED.severity,
                        matched_rule_ids = EXCLUDED.matched_rule_ids,
                        snippet = EXCLUDED.snippet,
                        snippet_tombstoned = FALSE,
                        status = CASE WHEN message_alerts.status IN ('RESOLVED', 'DISMISSED')
                                      THEN 'OPEN' ELSE message_alerts.status END,
                        updated_at = now()
                    """,
                    messageId, conversationId, senderId, seq, category, severity,
                    ruleIdArray, snippet, dryRun);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO message_alerts
                        (message_id, conversation_id, sender_id, message_seq, category, severity,
                         matched_rule_ids, snippet, status, dry_run)
                    VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?, 'OPEN', ?)
                    ON CONFLICT (message_id, category) DO NOTHING
                    """,
                    messageId, conversationId, senderId, seq, category, severity,
                    ruleIdArray, snippet, dryRun);
        }
    }

    public Optional<AlertRow> findById(UUID alertId) {
        return jdbcTemplate.query(SELECT + " WHERE a.id = ?",
                this::map, alertId).stream().findFirst();
    }

    /** Filtered queue page: worst severity first, then newest. */
    public AdminAuditService.Page<AlertRow> search(String status, String severity, String category,
                                                 Instant from, Instant to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            params.add(status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (severity != null && !severity.isBlank()) {
            where.append(" AND a.severity = ?");
            params.add(severity.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND a.category = ?");
            params.add(category.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (from != null) {
            where.append(" AND a.created_at >= ?");
            params.add(utc(from));
        }
        if (to != null) {
            where.append(" AND a.created_at <= ?");
            params.add(utc(to));
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_alerts a" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<AlertRow> items = jdbcTemplate.query(SELECT + where + """
                 ORDER BY CASE a.severity
                              WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
                              WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC,
                          a.created_at DESC, a.id
                 LIMIT ? OFFSET ?
                """, this::map, pageParams.toArray());
        long totalCount = total == null ? 0 : total;
        return new AdminAuditService.Page<>(items, page, size, totalCount,
                (long) (page + 1) * size < totalCount);
    }

    public List<NoteRow> notes(UUID alertId) {
        return jdbcTemplate.query("""
                SELECT id, alert_id, author_id, body, created_at
                FROM alert_notes WHERE alert_id = ? ORDER BY id
                """,
                (rs, rowNum) -> new NoteRow(rs.getLong("id"),
                        rs.getObject("alert_id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant()),
                alertId);
    }

    public void addNote(UUID alertId, UUID authorId, String body) {
        jdbcTemplate.update(
                "INSERT INTO alert_notes (alert_id, author_id, body) VALUES (?, ?, ?)",
                alertId, authorId, body);
    }

    public void updateStatus(UUID alertId, String status, UUID actorId, boolean resolved) {
        // Moving away from RESOLVED/DISMISSED clears the resolution evidence:
        // a reopened alert must not keep a resolver it no longer has.
        jdbcTemplate.update("""
                UPDATE message_alerts
                SET status = ?,
                    resolved_by = CASE WHEN ? THEN ? ELSE NULL END,
                    resolved_at = CASE WHEN ? THEN now() ELSE NULL END,
                    updated_at = now()
                WHERE id = ?
                """, status, resolved, actorId, resolved, alertId);
    }

    public void assign(UUID alertId, UUID assigneeId) {
        jdbcTemplate.update("""
                UPDATE message_alerts
                SET assignee_id = ?, assigned_at = now(), updated_at = now()
                WHERE id = ?
                """, assigneeId, alertId);
    }

    public void escalate(UUID alertId, boolean toAcknowledged) {
        jdbcTemplate.update("""
                UPDATE message_alerts
                SET escalated = TRUE,
                    status = CASE WHEN ? AND status = 'OPEN' THEN 'ACKNOWLEDGED' ELSE status END,
                    updated_at = now()
                WHERE id = ?
                """, toAcknowledged, alertId);
    }

    /** Bulk dismiss open/acknowledged alerts; returns the count actually closed. */
    public int bulkDismiss(List<UUID> ids, UUID actorId) {
        String idArray = ids.stream().map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));
        return jdbcTemplate.update("""
                UPDATE message_alerts
                SET status = 'DISMISSED', resolved_by = ?, resolved_at = now(), updated_at = now()
                WHERE id = ANY (?::uuid[]) AND status IN ('OPEN', 'ACKNOWLEDGED')
                """, actorId, idArray);
    }

    /** Tombstones stored snippets when the source message is deleted. */
    public void tombstoneSnippetForMessage(UUID messageId) {
        jdbcTemplate.update("""
                UPDATE message_alerts
                SET snippet = NULL, snippet_tombstoned = TRUE, updated_at = now()
                WHERE message_id = ? AND snippet_tombstoned = FALSE
                """, messageId);
    }

    /** Tombstones one sender's snippets in a conversation (post-hoc AI opt-out). */
    public void tombstoneSnippetsForSender(UUID conversationId, UUID senderId) {
        jdbcTemplate.update("""
                UPDATE message_alerts
                SET snippet = NULL, snippet_tombstoned = TRUE, updated_at = now()
                WHERE conversation_id = ? AND sender_id = ? AND snippet_tombstoned = FALSE
                """, conversationId, senderId);
    }

    /**
     * Deletes a bounded batch of closed alerts whose close time is older than
     * the cutoff. Close time keys on {@code resolved_at} — every close path
     * stamps it — falling back to {@code updated_at} (the last mutation, for a
     * closed row almost always the close write) and finally {@code created_at}
     * for legacy rows missing the stamp. Keying on {@code created_at} alone
     * would delete an old alert the moment it is resolved, losing evidence
     * staff just produced.
     */
    public int purgeClosed(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                DELETE FROM message_alerts WHERE id IN (
                    SELECT id FROM message_alerts
                    WHERE status IN ('RESOLVED', 'DISMISSED')
                      AND COALESCE(resolved_at, updated_at, created_at) <= ?
                    ORDER BY COALESCE(resolved_at, updated_at, created_at) LIMIT ?
                )
                """, utc(cutoff), batchSize);
    }

    /** Dry-run counterpart of {@link #purgeClosed}: same close-time filter. */
    public long countClosedOlderThan(Instant cutoff) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM message_alerts
                WHERE status IN ('RESOLVED', 'DISMISSED')
                  AND COALESCE(resolved_at, updated_at, created_at) <= ?
                """, Long.class, utc(cutoff));
        return value == null ? 0 : value;
    }

    private static final String SELECT = """
            SELECT a.id, a.message_id, a.conversation_id, a.sender_id, a.message_seq,
                   a.category, a.severity, a.matched_rule_ids, a.snippet,
                   a.snippet_tombstoned, a.status, a.assignee_id, a.assigned_at,
                   a.resolved_by, a.resolved_at, a.escalated, a.dry_run,
                   a.created_at, a.updated_at
            FROM message_alerts a
            """;

    private AlertRow map(ResultSet rs, int rowNum) throws SQLException {
        Array array = rs.getArray("matched_rule_ids");
        List<UUID> ruleIds = new ArrayList<>();
        if (array != null) {
            Object[] raw = (Object[]) array.getArray();
            for (Object item : raw) {
                ruleIds.add((UUID) item);
            }
        }
        return new AlertRow(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("sender_id", UUID.class),
                rs.getLong("message_seq"),
                rs.getString("category"),
                rs.getString("severity"),
                List.copyOf(ruleIds),
                rs.getString("snippet"),
                rs.getBoolean("snippet_tombstoned"),
                rs.getString("status"),
                rs.getObject("assignee_id", UUID.class),
                instant(rs, "assigned_at"),
                rs.getObject("resolved_by", UUID.class),
                instant(rs, "resolved_at"),
                rs.getBoolean("escalated"),
                rs.getBoolean("dry_run"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * pgjdbc cannot bind {@link Instant} ({@code setObject} throws "can't infer
     * the SQL type"); {@link OffsetDateTime} binds as a typed timestamptz
     * literal and keeps the exact instant under any session timezone.
     */
    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
