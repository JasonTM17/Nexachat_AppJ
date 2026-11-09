package com.nexachat.invite.dto;

import java.util.UUID;

/**
 * Outcome of a join attempt via an invite link.
 * Status values:
 * <ul>
 *   <li>{@code JOINED}: user was directly added as a member (approval not required).</li>
 *   <li>{@code PENDING}: approval required; join request created or already pending.</li>
 *   <li>{@code ALREADY_MEMBER}: caller was already a member of the group (idempotent no-op).</li>
 * </ul>
 */
public record JoinResponse(
        String status,
        UUID conversationId,
        UUID requestId,
        boolean created
) {
    public JoinResponse(String status, UUID conversationId, UUID requestId) {
        this(status, conversationId, requestId, false);
    }
}
