package com.nexachat.conversation.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /conversations/{id} body (plan §10.2) — ADMIN only, GROUP only. At
 * least one field must be present (checked in the service, which also owns
 * the non-blank rule for {@code title}); an empty {@code avatarUrl} string
 * clears the avatar.
 */
public record PatchGroupRequest(
        @Size(max = 80) String title,
        @Size(max = 255) String avatarUrl
) {
}
