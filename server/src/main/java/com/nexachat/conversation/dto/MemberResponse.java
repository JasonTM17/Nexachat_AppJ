package com.nexachat.conversation.dto;

import com.nexachat.conversation.ConversationMember;

import java.time.Instant;
import java.util.UUID;

/**
 * Role-carrying member view for GROUP surfaces — the group-detail member list
 * and the {@code CONVERSATION_UPDATED} payload need {@code role} for the
 * admin badge (phase-08), unlike the slim {@link ConversationMemberView} used
 * by the DIRECT path.
 */
public record MemberResponse(
        UUID userId,
        String displayName,
        String phone,
        String avatarUrl,
        ConversationMember.Role role,
        Instant joinedAt
) {
}
