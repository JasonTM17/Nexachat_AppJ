package com.nexachat.admin;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Analyst workflow over {@code message_alerts}: queue reads, status/assignment
 * mutations, notes, escalation, warn-user, bulk dismiss and — most sensitive —
 * the authorized plaintext reveal.
 *
 * <p>Every mutation is audited inside the mutation's own transaction
 * ({@link AdminAuditService#record}); every reveal is additionally
 * rate-limited per actor and re-checks the privacy boundary — E2EE
 * conversations and opted-out senders refuse, because the stored redacted
 * snippet is the only evidence those flows may produce.
 */
@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final AlertRuleRepository ruleRepository;
    private final AdminAuditService auditService;
    private final AdminUserService userService;
    private final ConversationRepository conversationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AdminProperties properties;
    private final Map<UUID, Deque<Instant>> revealWindows = new ConcurrentHashMap<>();

    public AlertService(AlertRepository alertRepository,
                        AlertRuleRepository ruleRepository,
                        AdminAuditService auditService,
                        AdminUserService userService,
                        ConversationRepository conversationRepository,
                        JdbcTemplate jdbcTemplate,
                        AdminProperties properties) {
        this.alertRepository = alertRepository;
        this.ruleRepository = ruleRepository;
        this.auditService = auditService;
        this.userService = userService;
        this.conversationRepository = conversationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** Queue row plus the display names of the rules that matched. */
    public record AlertSummary(UUID id, UUID messageId, UUID conversationId, UUID senderId,
                               long messageSeq, String category, String severity,
                               List<String> ruleNames, String snippet, boolean snippetTombstoned,
                               String status, UUID assigneeId, boolean escalated, boolean dryRun,
                               Instant createdAt) {
    }

    public record AlertDetail(AlertSummary alert, List<AlertRepository.NoteRow> notes,
                              String senderDisplayName) {
    }

    public record RevealedMessage(UUID id, long seq, UUID senderId, String content,
                                  Instant createdAt) {
    }

    public record RevealResult(UUID alertId, List<RevealedMessage> messages) {
    }

    @Transactional(readOnly = true)
    public AdminAuditService.Page<AlertSummary> list(String status, String severity,
                                                     String category, Instant from, Instant to,
                                                     int page, int size) {
        int safeSize = size <= 0 ? 25 : Math.min(size, 100);
        int safePage = Math.max(0, page);
        AdminAuditService.Page<AlertRepository.AlertRow> rows = alertRepository.search(
                status, severity, category, from, to, safePage, safeSize);
        return new AdminAuditService.Page<>(
                rows.items().stream().map(this::toSummary).toList(),
                rows.page(), rows.size(), rows.total(), rows.hasMore());
    }

    @Transactional(readOnly = true)
    public AlertDetail detail(UUID alertId) {
        AlertRepository.AlertRow row = requireAlert(alertId);
        String senderName = jdbcTemplate.query(
                        "SELECT display_name FROM users WHERE id = ?",
                        (rs, rowNum) -> rs.getString(1), row.senderId())
                .stream().findFirst().orElse(null);
        return new AlertDetail(toSummary(row), alertRepository.notes(alertId), senderName);
    }

    /** Status and/or assignment change; either field may be null to keep it. */
    @Transactional
    public AlertDetail update(UUID alertId, String status, UUID assigneeId, String reason,
                              UUID actorId, User.Role actorRole, String ip) {
        AlertRepository.AlertRow row = requireAlert(alertId);
        List<String> changes = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            AlertStatus next = AlertStatus.parse(status);
            if (next != AlertStatus.parse(row.status())) {
                boolean resolved = next == AlertStatus.RESOLVED || next == AlertStatus.DISMISSED;
                alertRepository.updateStatus(alertId, next.name(), actorId, resolved);
                changes.add("status=" + next.name());
            }
        }
        if (assigneeId != null && !assigneeId.equals(row.assigneeId())) {
            alertRepository.assign(alertId, assigneeId);
            changes.add("assignee=" + assigneeId);
        }
        if (!changes.isEmpty()) {
            auditService.record(actorId, actorRole, "ALERT_UPDATE", "alert", alertId.toString(),
                    reason, Map.of("changes", String.join(",", changes)), ip);
        }
        return detail(alertId);
    }

    @Transactional
    public void addNote(UUID alertId, String body, UUID actorId, User.Role actorRole, String ip) {
        if (body == null || body.isBlank() || body.trim().length() > 1000) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A note between 1 and 1000 characters is required");
        }
        requireAlert(alertId);
        alertRepository.addNote(alertId, actorId, body.trim());
        auditService.record(actorId, actorRole, "ALERT_NOTE", "alert", alertId.toString(),
                null, Map.of(), ip);
    }

    /**
     * {@code ESCALATE} flags the alert for senior review; {@code WARN_USER}
     * files a staff warning against the alert's sender through the same
     * audited path the user drawer uses.
     */
    @Transactional
    public AlertDetail action(UUID alertId, String action, String reason,
                              UUID actorId, User.Role actorRole, String ip) {
        AlertRepository.AlertRow row = requireAlert(alertId);
        switch (action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ESCALATE" -> {
                alertRepository.escalate(alertId, true);
                auditService.record(actorId, actorRole, "ALERT_ESCALATE", "alert",
                        alertId.toString(), reason, Map.of(), ip);
            }
            case "WARN_USER" -> {
                userService.warnUser(row.senderId(), alertId, reason, actorId, actorRole, ip);
            }
            default -> throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown action");
        }
        return detail(alertId);
    }

    /** Bulk dismiss; only open/acknowledged rows transition. */
    @Transactional
    public int bulkDismiss(List<UUID> ids, String reason, UUID actorId,
                           User.Role actorRole, String ip) {
        if (ids == null || ids.isEmpty() || ids.size() > 200) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Between 1 and 200 alert ids are required");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A dismissal reason is required");
        }
        int dismissed = alertRepository.bulkDismiss(ids, actorId);
        auditService.record(actorId, actorRole, "ALERT_BULK_DISMISS", "alert",
                ids.stream().map(UUID::toString).collect(Collectors.joining(",")),
                reason.trim(), Map.of("dismissed", dismissed), ip);
        return dismissed;
    }

    /**
     * Authorized plaintext reveal. Guards, in order: per-actor rate limit →
     * alert exists → conversation is not E2EE → sender has not opted out of
     * AI analysis → the neighboring plaintext window. Every outcome — success
     * and refusal — lands in the audit log.
     */
    @Transactional(readOnly = true)
    public RevealResult reveal(UUID alertId, String reason, UUID actorId,
                               User.Role actorRole, String ip) {
        if (!consumeRevealToken(actorId)) {
            auditService.recordAttempt(actorId, actorRole, "ALERT_REVEAL", "alert",
                    alertId.toString(), reason, Map.of("denied", "rate-limited"), ip);
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Too many reveal requests — try again later");
        }
        AlertRepository.AlertRow row = requireAlert(alertId);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            denyReveal(actorId, actorRole, alertId, reason, ip, "reason-required");
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "A reveal reason between 1 and 500 characters is required");
        }
        Boolean e2ee = conversationRepository.findById(row.conversationId())
                .map(com.nexachat.conversation.Conversation::isE2eeEnabled)
                .orElse(null);
        if (Boolean.TRUE.equals(e2ee)) {
            denyReveal(actorId, actorRole, alertId, reason, ip, "e2ee-conversation");
            throw ApiException.of(ApiErrorCode.FORBIDDEN,
                    "This conversation is end-to-end encrypted — there is nothing to reveal");
        }
        List<RevealedMessage> messages = jdbcTemplate.query("""
                        SELECT id, seq, sender_id, content, created_at
                        FROM messages
                        WHERE conversation_id = ? AND seq BETWEEN ? AND ?
                          AND deleted_at IS NULL AND type = 'TEXT'
                          AND encryption IS NULL AND content IS NOT NULL
                          AND sender_id NOT IN (
                              SELECT user_id FROM conversation_ai_opt_outs
                              WHERE conversation_id = ?)
                        ORDER BY seq LIMIT ?
                        """,
                (rs, rowNum) -> new RevealedMessage(
                        rs.getObject("id", UUID.class), rs.getLong("seq"),
                        rs.getObject("sender_id", UUID.class), rs.getString("content"),
                        timestamp(rs, "created_at")),
                row.conversationId(),
                Math.max(0, row.messageSeq() - properties.alerts().revealContextRadius()),
                row.messageSeq() + properties.alerts().revealContextRadius(),
                row.conversationId(),
                properties.alerts().revealContextRadius() * 2 + 1);
        // Opt-out gate re-checked here, not only at scan time: an opt-out taken
        // after the alert was filed still suppresses this sender's content.
        boolean optedOut = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM conversation_ai_opt_outs
                    WHERE conversation_id = ? AND user_id = ?
                )
                """, Boolean.class, row.conversationId(), row.senderId());
        if (Boolean.TRUE.equals(optedOut)) {
            denyReveal(actorId, actorRole, alertId, reason, ip, "sender-opted-out");
            throw ApiException.of(ApiErrorCode.FORBIDDEN,
                    "The sender opted this conversation out of analysis");
        }
        // REQUIRES_NEW: this method runs in a read-only transaction, and the
        // evidence of a successful reveal must survive regardless.
        auditService.recordAttempt(actorId, actorRole, "ALERT_REVEAL", "alert",
                alertId.toString(), reason.trim(), Map.of("revealed", messages.size()), ip);
        return new RevealResult(alertId, messages);
    }

    private void denyReveal(UUID actorId, User.Role actorRole, UUID alertId,
                            String reason, String ip, String denial) {
        auditService.recordAttempt(actorId, actorRole, "ALERT_REVEAL", "alert",
                alertId.toString(), reason, Map.of("denied", denial), ip);
    }

    /** Sliding-window token bucket over the configured per-minute reveal cap. */
    private boolean consumeRevealToken(UUID actorId) {
        int limit = (int) Math.max(1, properties.reveal().perMinute());
        Instant cutoff = Instant.now().minusSeconds(60);
        Deque<Instant> window = revealWindows.computeIfAbsent(actorId, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(Instant.now());
            return true;
        }
    }

    private AlertRepository.AlertRow requireAlert(UUID alertId) {
        return alertRepository.findById(alertId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.ALERT_NOT_FOUND, "Alert not found"));
    }

    private AlertSummary toSummary(AlertRepository.AlertRow row) {
        List<String> ruleNames = row.matchedRuleIds().stream()
                .map(id -> ruleRepository.findById(id).map(AlertRuleRow::name).orElse(id.toString()))
                .toList();
        return new AlertSummary(row.id(), row.messageId(), row.conversationId(), row.senderId(),
                row.messageSeq(), row.category(), row.severity(), ruleNames,
                row.snippet(), row.snippetTombstoned(), row.status(), row.assigneeId(),
                row.escalated(), row.dryRun(), row.createdAt());
    }

    private static Instant timestamp(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
