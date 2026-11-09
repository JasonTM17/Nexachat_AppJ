package com.nexachat.invite.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Representation of a group join request for listing and status views.
 */
public record JoinRequestResponse(
        UUID id,
        UUID conversationId,
        UserSummary user,
        String status,
        Instant createdAt,
        Instant reviewedAt,
        UUID reviewedBy
) {
    public record UserSummary(
            UUID id,
            String displayName,
            String avatarUrl
    ) {
    }
}
