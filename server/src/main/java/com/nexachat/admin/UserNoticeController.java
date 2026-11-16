package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The user-facing half of staff warnings: the account can read its own
 * notices and mark them read. Admin-side creation lives in
 * {@link AdminUserService#warnUser}.
 */
@RestController
@RequestMapping("/api/v1/users/me/notices")
public class UserNoticeController {

    private final JdbcTemplate jdbcTemplate;

    public UserNoticeController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Notice(UUID id, String kind, String body, UUID alertId,
                         Instant createdAt, Instant readAt) {
    }

    @GetMapping
    public List<Notice> list(@AuthenticationPrincipal JwtPrincipal principal) {
        UUID userId = requireUserId(principal);
        return jdbcTemplate.query("""
                SELECT id, kind, body, alert_id, created_at, read_at
                FROM user_notices WHERE user_id = ?
                ORDER BY created_at DESC LIMIT 100
                """,
                (rs, rowNum) -> new Notice(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("body"),
                        rs.getObject("alert_id", UUID.class),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("read_at"))),
                userId);
    }

    @PostMapping("/{noticeId}/read")
    public void markRead(@AuthenticationPrincipal JwtPrincipal principal,
                         @PathVariable UUID noticeId) {
        UUID userId = requireUserId(principal);
        int updated = jdbcTemplate.update("""
                UPDATE user_notices SET read_at = now()
                WHERE id = ? AND user_id = ? AND read_at IS NULL
                """, noticeId, userId);
        if (updated == 0) {
            // Idempotent for repeat calls: an already-read notice is fine, an
            // unknown or foreign one is not.
            boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM user_notices WHERE id = ? AND user_id = ?)",
                    Boolean.class, noticeId, userId);
            if (!Boolean.TRUE.equals(exists)) {
                throw ApiException.of(ApiErrorCode.NOTICE_NOT_FOUND, "Notice not found");
            }
        }
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
