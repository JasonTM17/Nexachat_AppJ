package com.nexachat.feed.admin;

import com.nexachat.admin.AdminAuditService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.report.Report;
import com.nexachat.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Staff moderation of the public feed: the report queue over posts and
 * comments, hide/unhide, and report resolution.
 *
 * <p>Every mutation writes one {@code admin_audit_log} row in the same
 * transaction through {@link AdminAuditService}, so a hide that cannot be
 * audited does not commit. Hiding is idempotent: hiding an already-hidden row
 * succeeds without a second audit row, and unhiding a visible row is a no-op.
 */
@Service
public class AdminFeedService {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedService.class);

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 25;

    private static final String REPORT_COLUMNS = """
            SELECT r.id, r.target_type, r.target_id, r.reason, r.details, r.message_snapshot,
                   r.status, r.created_at, r.reporter_id, ru.display_name AS reporter_name,
                   COALESCE(p.author_id, c.author_id) AS author_id,
                   COALESCE(pu.display_name, cu.display_name) AS author_name,
                   COALESCE(p.body, c.body) AS target_body,
                   (p.id IS NOT NULL AND p.deleted_at IS NOT NULL)
                       OR (c.id IS NOT NULL AND c.deleted_at IS NOT NULL) AS target_deleted,
                   (p.id IS NOT NULL AND p.hidden_at IS NOT NULL)
                       OR (c.id IS NOT NULL AND c.hidden_at IS NOT NULL) AS target_hidden,
                   COALESCE(p.created_at, c.created_at) AS target_created_at
            FROM reports r
            JOIN users ru ON ru.id = r.reporter_id
            LEFT JOIN public_posts p ON r.target_type = 'POST' AND p.id = r.target_id
            LEFT JOIN users pu ON pu.id = p.author_id
            LEFT JOIN public_comments c ON r.target_type = 'COMMENT' AND c.id = r.target_id
            LEFT JOIN users cu ON cu.id = c.author_id
            WHERE r.target_type IN ('POST', 'COMMENT')
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AdminAuditService auditService;

    public AdminFeedService(JdbcTemplate jdbc, AdminAuditService auditService) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.auditService = auditService;
    }

    /** Newest-first page of feed reports, optionally filtered by status. */
    @Transactional(readOnly = true)
    public AdminAuditService.Page<AdminFeedReportResponse> reports(String status, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Report.Status filter = parseStatus(status);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageSize)
                .addValue("offset", (long) pageNumber * pageSize);
        String where = filter == null ? "" : " AND r.status = :status";
        if (filter != null) {
            params.addValue("status", filter.name());
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM reports r WHERE r.target_type IN ('POST', 'COMMENT')"
                        + (filter == null ? "" : " AND r.status = :status"),
                params, Long.class);
        List<AdminFeedReportResponse> items = named.query(
                REPORT_COLUMNS + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                params, reportMapper());
        long totalCount = total == null ? 0 : total;
        return new AdminAuditService.Page<>(items, pageNumber, pageSize, totalCount,
                (long) (pageNumber + 1) * pageSize < totalCount);
    }

    /** Hides a post; idempotent, audited. */
    @Transactional
    public void hidePost(UUID actorId, User.Role actorRole, UUID postId, String reason, String ip) {
        String cleanReason = requireReason(reason);
        int updated = jdbc.update("""
                        UPDATE public_posts
                        SET hidden_at = now(), hidden_by = ?, hidden_reason = ?
                        WHERE id = ? AND hidden_at IS NULL
                        """, actorId, cleanReason, postId);
        requireTargetExists(updated, "public_posts", postId, ApiErrorCode.POST_NOT_FOUND);
        if (updated == 1) {
            auditService.record(actorId, actorRole, "FEED_POST_HIDE", "POST", postId.toString(),
                    cleanReason, Map.of("reason", cleanReason), ip);
            log.info("Feed post {} hidden by {}", postId, actorId);
        }
    }

    /** Unhides a post; idempotent, audited. */
    @Transactional
    public void unhidePost(UUID actorId, User.Role actorRole, UUID postId, String reason, String ip) {
        int updated = jdbc.update("""
                        UPDATE public_posts
                        SET hidden_at = NULL, hidden_by = NULL, hidden_reason = NULL
                        WHERE id = ? AND hidden_at IS NOT NULL
                        """, postId);
        requireTargetExists(updated, "public_posts", postId, ApiErrorCode.POST_NOT_FOUND);
        if (updated == 1) {
            auditService.record(actorId, actorRole, "FEED_POST_UNHIDE", "POST", postId.toString(),
                    reason, null, ip);
            log.info("Feed post {} unhidden by {}", postId, actorId);
        }
    }

    /**
     * Hides a comment; idempotent, audited, and moves the post's comment count.
     * A comment its author already deleted is left alone: the delete already
     * moved the count, so hiding it must not decrement a second time.
     */
    @Transactional
    public void hideComment(UUID actorId, User.Role actorRole, UUID commentId, String reason,
                            String ip) {
        String cleanReason = requireReason(reason);
        List<UUID> changed = jdbc.queryForList("""
                        UPDATE public_comments
                        SET hidden_at = now(), hidden_by = ?, hidden_reason = ?
                        WHERE id = ? AND hidden_at IS NULL AND deleted_at IS NULL
                        RETURNING post_id
                        """, UUID.class, actorId, cleanReason, commentId);
        if (changed.isEmpty()) {
            requireTargetExists(0, "public_comments", commentId, ApiErrorCode.COMMENT_NOT_FOUND);
            return;
        }
        jdbc.update("""
                        UPDATE public_posts
                        SET comment_count = GREATEST(comment_count - 1, 0)
                        WHERE id = ?
                        """, changed.get(0));
        auditService.record(actorId, actorRole, "FEED_COMMENT_HIDE", "COMMENT",
                commentId.toString(), cleanReason, Map.of("reason", cleanReason), ip);
        log.info("Feed comment {} hidden by {}", commentId, actorId);
    }

    /**
     * Unhides a comment; idempotent, audited, and moves the post's comment
     * count. Like hide, it skips comments the author already deleted.
     */
    @Transactional
    public void unhideComment(UUID actorId, User.Role actorRole, UUID commentId, String reason,
                              String ip) {
        List<UUID> changed = jdbc.queryForList("""
                        UPDATE public_comments
                        SET hidden_at = NULL, hidden_by = NULL, hidden_reason = NULL
                        WHERE id = ? AND hidden_at IS NOT NULL AND deleted_at IS NULL
                        RETURNING post_id
                        """, UUID.class, commentId);
        if (changed.isEmpty()) {
            requireTargetExists(0, "public_comments", commentId, ApiErrorCode.COMMENT_NOT_FOUND);
            return;
        }
        jdbc.update("UPDATE public_posts SET comment_count = comment_count + 1 WHERE id = ?",
                changed.get(0));
        auditService.record(actorId, actorRole, "FEED_COMMENT_UNHIDE", "COMMENT",
                commentId.toString(), reason, null, ip);
        log.info("Feed comment {} unhidden by {}", commentId, actorId);
    }

    /** Resolves a feed report to {@code REVIEWED} or {@code DISMISSED}; audited. */
    @Transactional
    public void resolveReport(UUID actorId, User.Role actorRole, UUID reportId, String status,
                              String ip) {
        Report.Status target = parseResolution(status);
        int updated = jdbc.update("""
                        UPDATE reports SET status = ?
                        WHERE id = ? AND target_type IN ('POST', 'COMMENT')
                        """, target.name(), reportId);
        if (updated == 0) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Report not found");
        }
        auditService.record(actorId, actorRole, "FEED_REPORT_RESOLVE", "REPORT",
                reportId.toString(), null, Map.of("status", target.name()), ip);
        log.info("Feed report {} resolved as {} by {}", reportId, target, actorId);
    }

    private void requireTargetExists(int updated, String table, UUID id, ApiErrorCode missing) {
        if (updated == 1) {
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw ApiException.of(missing);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "reason is required");
        }
        String clean = reason.strip();
        if (clean.length() > 500) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "reason must be at most 500 characters");
        }
        return clean;
    }

    private static Report.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Report.Status.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown report status");
        }
    }

    private static Report.Status parseResolution(String raw) {
        Report.Status status = parseStatus(raw);
        if (status == null || status == Report.Status.OPEN) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "status must be REVIEWED or DISMISSED");
        }
        return status;
    }

    private static RowMapper<AdminFeedReportResponse> reportMapper() {
        return (rs, rowNum) -> new AdminFeedReportResponse(
                rs.getObject("id", UUID.class),
                Report.TargetType.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class),
                Report.Reason.valueOf(rs.getString("reason")),
                rs.getString("details"),
                rs.getString("message_snapshot"),
                Report.Status.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                rs.getObject("reporter_id", UUID.class),
                rs.getString("reporter_name"),
                rs.getObject("author_id", UUID.class),
                rs.getString("author_name"),
                rs.getString("target_body"),
                rs.getBoolean("target_deleted"),
                rs.getBoolean("target_hidden"),
                instant(rs, "target_created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
