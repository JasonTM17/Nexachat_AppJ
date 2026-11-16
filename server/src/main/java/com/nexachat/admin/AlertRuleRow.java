package com.nexachat.admin;

import java.time.Instant;
import java.util.UUID;

/** One {@code alert_rules} row as scanned and rendered by the admin console. */
public record AlertRuleRow(UUID id, String name, String category, String severity,
                           String patternType, String pattern, String locale,
                           boolean builtIn, boolean enabled, String description,
                           UUID createdBy, Instant createdAt, Instant updatedAt) {
}
