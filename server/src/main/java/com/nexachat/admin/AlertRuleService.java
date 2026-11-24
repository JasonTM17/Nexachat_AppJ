package com.nexachat.admin;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for {@code alert_rules}. Built-in rules are immutable — they may only
 * be toggled or re-severitied — while custom rules are fully editable and
 * deletable. Every pattern is validated at write time by
 * {@link SensitiveMessageScanner#validatePattern} so a bad regex can never
 * reach the scan loop.
 */
@Service
public class AlertRuleService {

    /** Stored in {@code pattern} for CARD rules — the column is NOT NULL and the detector ignores it. */
    private static final String CARD_PATTERN_SENTINEL = "card-detector";

    private final AlertRuleRepository repository;
    private final SensitiveMessageScanner scanner;
    private final AdminAuditService auditService;

    public AlertRuleService(AlertRuleRepository repository,
                            SensitiveMessageScanner scanner,
                            AdminAuditService auditService) {
        this.repository = repository;
        this.scanner = scanner;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AlertRuleRow> list() {
        return repository.findAll();
    }

    @Transactional
    public AlertRuleRow create(String name, String category, String severity,
                               String patternType, String pattern, String locale,
                               String description, UUID actorId, User.Role actorRole, String ip) {
        requireAdmin(actorRole);
        AlertCategory cat = AlertCategory.parse(category);
        AlertSeverity sev = AlertSeverity.parse(severity);
        String type = normalizeType(patternType);
        scanner.validatePattern(type, pattern);
        UUID id = UUID.randomUUID();
        repository.insert(id, requireText(name, "name", 200), cat.name(), sev.name(), type,
                "CARD".equals(type) ? CARD_PATTERN_SENTINEL : pattern.trim(), locale,
                false, description, actorId);
        auditService.record(actorId, actorRole, "RULE_CREATE", "alert_rule", id.toString(),
                null, Map.of("name", name, "category", cat.name()), ip);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public AlertRuleRow update(UUID ruleId, String name, String severity, String pattern,
                               String description, Boolean enabled, String reason,
                               UUID actorId, User.Role actorRole, String ip) {
        requireAdmin(actorRole);
        AlertRuleRow existing = requireRule(ruleId);
        if (existing.builtIn() && (name != null || pattern != null || description != null)) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Built-in rules can only change severity or be enabled and disabled");
        }
        String newSeverity = severity == null || severity.isBlank()
                ? existing.severity() : AlertSeverity.parse(severity).name();
        String newPattern = "CARD".equals(existing.patternType())
                ? CARD_PATTERN_SENTINEL
                : (pattern == null ? existing.pattern() : pattern);
        if (!"CARD".equals(existing.patternType())) {
            scanner.validatePattern(existing.patternType(), newPattern);
        }
        String newName = name == null || name.isBlank() ? existing.name()
                : requireText(name, "name", 200);
        boolean newEnabled = enabled == null ? existing.enabled() : enabled;
        repository.update(ruleId, newName, newSeverity, newPattern, description == null
                ? existing.description() : description, newEnabled);
        // C5-03: the rule editor's optional justification lands on the audit
        // row instead of the fixed null: old clients that send no reason keep
        // auditing exactly as before.
        auditService.record(actorId, actorRole, "RULE_UPDATE", "alert_rule", ruleId.toString(),
                reason, Map.of("enabled", newEnabled, "severity", newSeverity), ip);
        return repository.findById(ruleId).orElseThrow();
    }

    @Transactional
    public void delete(UUID ruleId, UUID actorId, User.Role actorRole, String ip) {
        requireAdmin(actorRole);
        AlertRuleRow existing = requireRule(ruleId);
        if (existing.builtIn()) {
            // Contract: DELETE on a built-in disables it instead of refusing.
            repository.setEnabled(ruleId, false);
            auditService.record(actorId, actorRole, "RULE_UPDATE", "alert_rule", ruleId.toString(),
                    null, Map.of("enabled", false, "severity", existing.severity(),
                            "name", existing.name()), ip);
            return;
        }
        repository.delete(ruleId);
        auditService.record(actorId, actorRole, "RULE_DELETE", "alert_rule", ruleId.toString(),
                null, Map.of("name", existing.name()), ip);
    }

    /**
     * Dry-run an arbitrary pattern against operator-supplied sample text.
     * Returns match offsets and the same redacted snippet the pipeline would
     * store — the rule editor's live preview.
     */
    public Map<String, Object> test(String patternType, String pattern, String sampleText) {
        String type = normalizeType(patternType);
        scanner.validatePattern(type, pattern);
        String sample = sampleText == null ? "" : sampleText;
        AlertRuleRow probe = new AlertRuleRow(UUID.randomUUID(), "probe",
                "CUSTOM", "MEDIUM", type, "CARD".equals(type) ? CARD_PATTERN_SENTINEL : pattern,
                "any", false, true, null, null, null, null);
        List<SensitiveMessageScanner.Match> matches = scanner.scan(sample, List.of(probe));
        List<Map<String, Object>> hits = matches.stream()
                .map(m -> Map.<String, Object>of(
                        "offset", m.offset(),
                        "length", m.length(),
                        "snippet", scanner.snippet(sample, m, 80)))
                .toList();
        return Map.of("matched", !hits.isEmpty(), "matches", hits);
    }

    private AlertRuleRow requireRule(UUID ruleId) {
        return repository.findById(ruleId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.ALERT_RULE_NOT_FOUND,
                        "Rule not found"));
    }

    private void requireAdmin(User.Role actorRole) {
        if (actorRole != User.Role.ADMIN) {
            throw ApiException.of(ApiErrorCode.NOT_ADMIN, "Admin role required");
        }
    }

    private static String normalizeType(String patternType) {
        String type = patternType == null ? "" : patternType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!type.equals("KEYWORD") && !type.equals("REGEX") && !type.equals("CARD")) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown pattern type");
        }
        return type;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    field + " must be 1-" + max + " characters");
        }
        return value.trim();
    }
}
