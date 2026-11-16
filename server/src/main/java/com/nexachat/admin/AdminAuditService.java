package com.nexachat.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only admin audit log.
 *
 * <p>Every admin mutation and every content reveal writes one row carrying
 * actor, action, target, reason and client IP. The {@code admin_audit_log}
 * table is guarded by an immutability trigger (V36) so rows cannot be updated
 * or deleted afterwards — including by this service, which intentionally has
 * no write path other than INSERT. Rows deliberately carry no FK to users:
 * the record must survive account deletion and user-table truncation.
 *
 * <p>Two write modes: {@link #record} joins the caller's transaction so the
 * audit row commits exactly when the audited mutation does; {@link
 * #recordAttempt} runs {@code REQUIRES_NEW} so a denied attempt (last-admin
 * guard, rate-limited reveal) is captured even though the action itself then
 * fails and rolls back. A failed insert propagates in both modes: an action
 * that cannot be audited must not commit silently.
 */
@Service
public class AdminAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** One row of the audit log as returned by {@link #search}. */
    public record Entry(long id, UUID actorId, String actorRole, String action,
                        String targetType, String targetId, String reason,
                        Map<String, Object> detail, String ip, Instant createdAt) {
    }

    /** Offset page wrapper shared by the admin list endpoints. */
    public record Page<T>(List<T> items, int page, int size, long total, boolean hasMore) {
    }

    /**
     * Appends one immutable audit row inside the caller's transaction — the
     * row and the mutation it describes commit or roll back together.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorId, User.Role actorRole, String action,
                       String targetType, String targetId, String reason,
                       Map<String, Object> detail, String ip) {
        insert(actorId, actorRole, action, targetType, targetId, reason, detail, ip);
    }

    /**
     * Appends an audit row for a denied or failed attempt in its own
     * transaction, so the surrounding failure cannot silently erase the
     * evidence that the attempt was made.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(UUID actorId, User.Role actorRole, String action,
                              String targetType, String targetId, String reason,
                              Map<String, Object> detail, String ip) {
        insert(actorId, actorRole, action, targetType, targetId, reason, detail, ip);
    }

    private void insert(UUID actorId, User.Role actorRole, String action,
                        String targetType, String targetId, String reason,
                        Map<String, Object> detail, String ip) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_log
                    (actor_id, actor_role, action, target_type, target_id, reason, detail, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                actorId,
                actorRole == null ? "UNKNOWN" : actorRole.name(),
                action,
                targetType,
                targetId,
                reason,
                detail == null ? null : toJson(detail),
                ip);
    }

    /** Filtered, newest-first page over the audit log. */
    @Transactional(readOnly = true)
    public Page<Entry> search(UUID actorId, String action, String targetType, String targetId,
                              Instant from, Instant to, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (actorId != null) {
            where.append(" AND actor_id = ?");
            params.add(actorId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND action = ?");
            params.add(action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" AND target_type = ?");
            params.add(targetType.trim());
        }
        if (targetId != null && !targetId.isBlank()) {
            where.append(" AND target_id = ?");
            params.add(targetId.trim());
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            params.add(from);
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            params.add(to);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_audit_log" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<Entry> items = jdbcTemplate.query("""
                        SELECT id, actor_id, actor_role, action, target_type, target_id,
                               reason, detail::text AS detail, ip, created_at
                        FROM admin_audit_log""" + where + """
                         ORDER BY id DESC LIMIT ? OFFSET ?
                        """,
                this::mapEntry, pageParams.toArray());
        long totalCount = total == null ? 0 : total;
        return new Page<>(items, page, size, totalCount, (long) (page + 1) * size < totalCount);
    }

    private Entry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        String detailText = rs.getString("detail");
        Map<String, Object> detail = null;
        if (detailText != null) {
            try {
                detail = objectMapper.readValue(detailText, Map.class);
            } catch (JsonProcessingException e) {
                detail = Map.of("unparsed", detailText);
            }
        }
        return new Entry(rs.getLong("id"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("reason"),
                detail,
                rs.getString("ip"),
                rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
