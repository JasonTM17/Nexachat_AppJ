package com.nexachat.invite.dto;

import java.util.UUID;

/**
 * Safe preview of a group for an invite link.
 * Never includes member lists to protect member privacy.
 */
public record InvitePreviewResponse(
        UUID conversationId,
        String title,
        String avatarUrl,
        int memberCount,
        boolean requiresApproval,
        boolean isMember
) {
}
