package com.nexachat.invite.dto;

import java.time.Instant;

/**
 * Request payload for creating or rotating a group invite link.
 */
public record CreateInviteLinkRequest(
        Instant expiresAt,
        Integer maxUses,
        Boolean requiresApproval
) {
}
