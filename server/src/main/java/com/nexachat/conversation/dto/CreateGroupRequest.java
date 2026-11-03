package com.nexachat.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * POST /conversations/group body (plan §10.2). {@code memberIds} lists OTHER
 * members — the creator is added implicitly as ADMIN. The service dedupes the
 * list and silently drops the creator's own id if present, then requires at
 * least one remaining member (a GROUP needs ≥ 2 people, plan phase-08).
 */
public record CreateGroupRequest(
        @NotBlank @Size(max = 80) String title,
        @NotEmpty List<UUID> memberIds
) {
}
