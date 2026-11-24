package com.nexachat.admin;

import com.nexachat.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C5-03: the rule editor's optional PATCH reason lands on the audit row. */
@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTest {

    @Mock
    private AlertRuleRepository repository;
    @Mock
    private SensitiveMessageScanner scanner;
    @Mock
    private AdminAuditService auditService;

    private AlertRuleService service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AlertRuleService(repository, scanner, auditService);
    }

    @Test
    void patchedReasonIsAuditedWithTheRuleUpdate() {
        AlertRuleRow rule = customRule();
        when(repository.findById(ruleId)).thenReturn(Optional.of(rule), Optional.of(rule));

        AlertRuleRow updated = service.update(ruleId, null, null, null, null, true,
                "disabling during the payments migration", actorId, User.Role.ADMIN, "203.0.113.7");

        assertThat(updated).isSameAs(rule);
        verify(auditService).record(eq(actorId), eq(User.Role.ADMIN), eq("RULE_UPDATE"),
                eq("alert_rule"), eq(ruleId.toString()), eq("disabling during the payments migration"),
                eq(Map.of("enabled", true, "severity", "MEDIUM")), eq("203.0.113.7"));
    }

    @Test
    void patchedRuleWithoutAReasonAuditsANullReason() {
        AlertRuleRow rule = customRule();
        when(repository.findById(ruleId)).thenReturn(Optional.of(rule), Optional.of(rule));

        service.update(ruleId, null, null, null, null, false, null,
                actorId, User.Role.ADMIN, "203.0.113.7");

        verify(auditService).record(eq(actorId), eq(User.Role.ADMIN), eq("RULE_UPDATE"),
                eq("alert_rule"), eq(ruleId.toString()), isNull(),
                eq(Map.of("enabled", false, "severity", "MEDIUM")), eq("203.0.113.7"));
    }

    private AlertRuleRow customRule() {
        return new AlertRuleRow(ruleId, "Payments keyword", "CUSTOM", "MEDIUM", "KEYWORD",
                "invoice", "any", false, true, null, actorId, Instant.EPOCH, Instant.EPOCH);
    }
}
