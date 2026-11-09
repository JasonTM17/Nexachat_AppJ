package com.nexachat.invite.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata of a group invite link.
 * {@code token} is populated only when creating/rotating a link so the admin can copy it.
 */
public record InviteLinkResponse(
        UUID id,
        UUID conversationId,
        String token,
        boolean requiresApproval,
        Instant expiresAt,
        Integer maxUses,
        int useCount,
        Instant createdAt
) {
}
