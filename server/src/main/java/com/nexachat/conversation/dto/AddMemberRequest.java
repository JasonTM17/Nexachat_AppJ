package com.nexachat.conversation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * POST /conversations/{id}/members body (plan §10.2) — ADMIN only. Adding is
 * idempotent: ids already in the group are skipped, not errors.
 */
public record AddMemberRequest(
        @NotEmpty List<UUID> userIds
) {
}
