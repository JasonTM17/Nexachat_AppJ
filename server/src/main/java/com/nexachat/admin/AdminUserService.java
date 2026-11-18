package com.nexachat.admin;

import com.nexachat.auth.AccessTokenRevocationService;
import com.nexachat.auth.AccessTokenValidityChangedEvent;
import com.nexachat.auth.TokenService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account administration: filtered user listing, per-account detail counters
 * and the destructive lifecycle actions (suspend / unsuspend / revoke
 * sessions / role change / warn).
 *
 * <p>Guards baked in here rather than the controller so every caller gets the
 * same contract:
 * <ul>
 *   <li>an administrator can never act destructively on their own account;</li>
 *   <li>a MODERATOR may only act on plain USER accounts — staff accounts
 *       require an ADMIN actor;</li>
 *   <li>the last ADMIN can never be demoted;</li>
 *   <li>suspension revokes every refresh token and stamps the access-token
 *       cutoff through {@link AccessTokenRevocationService}, so the account
 *       is locked out of login, refresh, existing tokens, WebSocket and send.</li>
 * </ul>
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final AccessTokenRevocationService accessTokenRevocationService;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AdminUserService(JdbcTemplate jdbcTemplate,
                            UserRepository userRepository,
                            TokenService tokenService,
                            AccessTokenRevocationService accessTokenRevocationService,
                            AdminAuditService auditService,
                            ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.accessTokenRevocationService = accessTokenRevocationService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /** List row: profile + lifecycle flags. */
    public record UserSummary(UUID id, String phone, String displayName, String avatarUrl,
                              String email, String role, Instant suspendedAt,
                              String suspendedReason, Instant createdAt, Instant lastSeenAt,
                              Instant deletedAt) {
    }

    /** Detail adds the activity counters the drawer shows. */
    public record UserDetail(UserSummary user, long deviceCount, long sessionCount,
                             long conversationCount, long alertCount) {
    }

    /** Caller-facing notice row for the warn-user drawer. */
    public record NoticeResult(UUID id, String kind, String body, Instant createdAt) {
    }

    /**
     * Paginated account list. {@code status} is {@code active|suspended|deleted};
     * {@code search} is a LIKE-escaped substring on phone, display name or email.
     */
    @Transactional(readOnly = true)
    public AdminAuditService.Page<UserSummary> list(String search, String status, String role,
                                                    Instant createdFrom, Instant createdTo,
                                                    int page, int size) {
        int safeSize = size <= 0 ? 25 : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append("""
                     AND (phone ILIKE ? ESCAPE '\\'
                       OR display_name ILIKE ? ESCAPE '\\'
                       OR email ILIKE ? ESCAPE '\\')
                    """);
            String pattern = "%" + escapeLike(search.trim()) + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (status != null && !status.isBlank()) {
            switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "active" -> where.append(" AND deleted_at IS NULL AND suspended_at IS NULL");
                case "suspended" -> where.append(" AND deleted_at IS NULL AND suspended_at IS NOT NULL");
                case "deleted" -> where.append(" AND deleted_at IS NOT NULL");
                default -> throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown status filter");
            }
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND role = ?");
            params.add(parseRole(role).name());
        }
        if (createdFrom != null) {
            where.append(" AND created_at >= ?");
            params.add(createdFrom);
        }
        if (createdTo != null) {
            where.append(" AND created_at <= ?");
            params.add(createdTo);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize);
        pageParams.add((long) safePage * safeSize);
        List<UserSummary> items = jdbcTemplate.query("""
                        SELECT id, phone, display_name, avatar_url, email, role,
                               suspended_at, suspended_reason, created_at, last_seen_at, deleted_at
                        FROM users""" + where + """
                         ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                        """,
                this::mapSummary, pageParams.toArray());
        long totalCount = total == null ? 0 : total;
        return new AdminAuditService.Page<>(items, safePage, safeSize, totalCount,
                (long) (safePage + 1) * safeSize < totalCount);
    }

    /** Scheduled-deletion view: tombstoned accounts, newest first, bounded. */
    @Transactional(readOnly = true)
    public List<UserSummary> deletedAccounts(int limit) {
        int capped = limit <= 0 ? 50 : Math.min(limit, 200);
        return jdbcTemplate.query("""
                        SELECT id, phone, display_name, avatar_url, email, role,
                               suspended_at, suspended_reason, created_at, last_seen_at, deleted_at
                        FROM users
                        WHERE deleted_at IS NOT NULL
                        ORDER BY deleted_at DESC, id
                        LIMIT ?
                        """,
                this::mapSummary, capped);
    }

    /** Profile plus the device/session/conversation/alert counters. */
    @Transactional(readOnly = true)
    public UserDetail detail(UUID userId) {
        UserSummary user = jdbcTemplate.query("""
                        SELECT id, phone, display_name, avatar_url, email, role,
                               suspended_at, suspended_reason, created_at, last_seen_at, deleted_at
                        FROM users WHERE id = ?
                        """,
                this::mapSummary, userId).stream().findFirst()
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        long devices = count("SELECT count(*) FROM user_device_tokens WHERE user_id = ?", userId);
        long sessions = count(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", userId);
        long conversations = count(
                "SELECT count(*) FROM conversation_members WHERE user_id = ?", userId);
        long alerts = count("SELECT count(*) FROM message_alerts WHERE sender_id = ?", userId);
        return new UserDetail(user, devices, sessions, conversations, alerts);
    }

    /**
     * Suspends the account: stamps the flag, revokes every refresh token and
     * stamps the access-token cutoff, all inside one transaction with the
     * STOMP write fence held until completion. Re-suspending is idempotent —
     * the stored reason is updated but no second revocation is needed.
     */
    @Transactional
    public void suspend(UUID targetId, String reason, UUID actorId, User.Role actorRole, String ip) {
        requireReason(reason);
        // Lock the admin set only when the target can actually be an admin —
        // an unlocked pre-read sizes the lock set so plain-account suspends
        // never queue behind unrelated admin rows. The authoritative role is
        // re-read under the target's row lock in requireActionableTarget.
        int activeAdmins = isAdminTarget(targetId) ? lockedAdminCount() : -1;
        User target = requireActionableTarget(targetId, actorId, actorRole);
        // The final administrator cannot lose their account — demotion has the
        // same guard, and suspension is the operational equivalent. When the
        // pre-read was stale (role became ADMIN between read and lock,
        // activeAdmins stays -1) the guard fails closed: the admin set was
        // never locked, so no count it yields can be trusted.
        if (target.getRole() == User.Role.ADMIN
                && activeAdmins <= 1) {
            denyAudit(actorId, actorRole, "USER_SUSPEND", targetId, reason, ip, "last-admin");
            throw ApiException.of(ApiErrorCode.LAST_ADMIN, "Cannot suspend the last admin");
        }
        if (!target.isSuspended()) {
            target.suspend(Instant.now(), reason.trim(), actorId);
            userRepository.save(target);
            tokenService.revokeAllForUser(targetId);
            accessTokenRevocationService.revoke(targetId);
        } else {
            target.suspend(target.getSuspendedAt(), reason.trim(), actorId);
            userRepository.save(target);
        }
        auditService.record(actorId, actorRole, "USER_SUSPEND", "user", targetId.toString(),
                reason.trim(), Map.of("phone", maskPhone(target)), ip);
    }

    @Transactional
    public void unsuspend(UUID targetId, String reason, UUID actorId, User.Role actorRole, String ip) {
        User target = requireActionableTarget(targetId, actorId, actorRole);
        if (target.isSuspended()) {
            target.unsuspend();
            userRepository.save(target);
            // Tokens stay valid; only the cached validity snapshot is stale.
            eventPublisher.publishEvent(new AccessTokenValidityChangedEvent(targetId));
        }
        auditService.record(actorId, actorRole, "USER_UNSUSPEND", "user", targetId.toString(),
                reason == null ? null : reason.trim(), Map.of("phone", maskPhone(target)), ip);
    }

    /** Force-logout: every refresh token revoked plus the access-token cutoff. */
    @Transactional
    public void revokeSessions(UUID targetId, String reason, UUID actorId, User.Role actorRole, String ip) {
        User target = requireActionableTarget(targetId, actorId, actorRole);
        tokenService.revokeAllForUser(targetId);
        accessTokenRevocationService.revoke(targetId);
        auditService.record(actorId, actorRole, "USER_REVOKE_SESSIONS", "user", targetId.toString(),
                reason == null ? null : reason.trim(), Map.of("phone", maskPhone(target)), ip);
    }

    /**
     * Role change — ADMIN-only, never self, never the last remaining ADMIN.
     * The privilege change revokes sessions so a demoted or promoted account
     * re-authenticates under its new role.
     */
    @Transactional
    public void changeRole(UUID targetId, User.Role newRole, String reason,
                           UUID actorId, User.Role actorRole, String ip) {
        if (actorRole != User.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Admin role required");
        }
        requireReason(reason);
        if (targetId.equals(actorId)) {
            denyAudit(actorId, actorRole, "USER_CHANGE_ROLE", targetId, reason, ip,
                    "self-role-change");
            throw ApiException.of(ApiErrorCode.FORBIDDEN, "You cannot change your own role");
        }
        // Only a demotion can hit the last-admin invariant, and only an ADMIN
        // target needs the set locked first — the same unlocked pre-read and
        // under-lock re-validation as suspend.
        int activeAdmins = newRole != User.Role.ADMIN && isAdminTarget(targetId)
                ? lockedAdminCount() : -1;
        User target = userRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (target.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        if (target.getRole() == User.Role.ADMIN && newRole != User.Role.ADMIN
                && activeAdmins <= 1) {
            denyAudit(actorId, actorRole, "USER_CHANGE_ROLE", targetId, reason, ip, "last-admin");
            throw ApiException.of(ApiErrorCode.LAST_ADMIN, "Cannot demote the last admin");
        }
        target.changeRole(newRole);
        userRepository.save(target);
        tokenService.revokeAllForUser(targetId);
        accessTokenRevocationService.revoke(targetId);
        auditService.record(actorId, actorRole, "USER_CHANGE_ROLE", "user", targetId.toString(),
                reason.trim(), Map.of("newRole", newRole.name(), "phone", maskPhone(target)), ip);
    }

    /** Non-locking peek at the target's role — only sizes the lock set. */
    private boolean isAdminTarget(UUID targetId) {
        return userRepository.findRoleById(targetId)
                .map(role -> role == User.Role.ADMIN)
                .orElse(false);
    }

    /**
     * Counts active admins while holding a row lock on all of them, so two
     * concurrent suspend/demote actions on different admins serialize and the
     * last-admin invariant cannot be bypassed.
     */
    private int lockedAdminCount() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE role = 'ADMIN' AND deleted_at IS NULL ORDER BY id FOR UPDATE",
                UUID.class).size();
    }

    /** Staff warning notice shown to the user; optionally bound to an alert. */
    @Transactional
    public NoticeResult warnUser(UUID targetId, UUID alertId, String reason,
                                 UUID actorId, User.Role actorRole, String ip) {
        requireReason(reason);
        requireActionableTarget(targetId, actorId, actorRole);
        // The cited alert must be the target's own: a warning notice bound to
        // an alert raised on someone else's message would mislink evidence.
        if (alertId != null && !Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM message_alerts WHERE id = ? AND sender_id = ?)",
                Boolean.class, alertId, targetId))) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown alert");
        }
        String body = "Your messages violated the community rules (" + reason.trim() + ")";
        UUID noticeId = jdbcTemplate.queryForObject("""
                INSERT INTO user_notices (user_id, alert_id, kind, body, created_by)
                VALUES (?, ?, 'WARNING', ?, ?) RETURNING id
                """, UUID.class, targetId, alertId, body.length() > 1000
                ? body.substring(0, 1000) : body, actorId);
        auditService.record(actorId, actorRole, "USER_WARN", "user", targetId.toString(),
                reason.trim(), Map.of("noticeId", String.valueOf(noticeId),
                        "alertId", alertId == null ? "" : alertId.toString()), ip);
        return new NoticeResult(noticeId, "WARNING", body, Instant.now());
    }

    private User requireActionableTarget(UUID targetId, UUID actorId, User.Role actorRole) {
        if (targetId.equals(actorId)) {
            throw ApiException.of(ApiErrorCode.FORBIDDEN, "You cannot perform this action on yourself");
        }
        User target = userRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (target.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        // A moderator's reach stops at plain accounts: staff accounts always
        // require an ADMIN actor, otherwise a moderator could silence an admin.
        if (actorRole == User.Role.MODERATOR && target.getRole() != User.Role.USER) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Admin role required for staff accounts");
        }
        return target;
    }

    private void denyAudit(UUID actorId, User.Role actorRole, String action, UUID targetId,
                           String reason, String ip, String denial) {
        auditService.recordAttempt(actorId, actorRole, action, "user", targetId.toString(),
                reason, Map.of("denied", denial), ip);
    }

    private long count(String sql, Object arg) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arg);
        return value == null ? 0 : value;
    }

    private UserSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new UserSummary(
                rs.getObject("id", UUID.class),
                rs.getString("phone"),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("email"),
                rs.getString("role"),
                instant(rs, "suspended_at"),
                rs.getString("suspended_reason"),
                instant(rs, "created_at"),
                instant(rs, "last_seen_at"),
                instant(rs, "deleted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A reason between 1 and 500 characters is required");
        }
    }

    private static User.Role parseRole(String raw) {
        try {
            return User.Role.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown role");
        }
    }

    private static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Audit rows carry a masked phone: the actor id is already the correlation key. */
    private static String maskPhone(User user) {
        String phone = user.getPhone();
        if (phone == null || phone.length() < 5) {
            return "";
        }
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 2);
    }
}
