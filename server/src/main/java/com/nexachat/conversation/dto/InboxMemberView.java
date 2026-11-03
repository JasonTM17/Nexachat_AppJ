package com.nexachat.conversation.dto;

import java.util.UUID;

/**
 * Batch member read model for the inbox: like {@link ConversationMemberView}
 * but carries the owning {@code conversationId} for grouping and
 * {@code avatarUrl} for the entry rows (inbox spec needs all four fields).
 */
public record InboxMemberView(
        UUID conversationId,
        UUID userId,
        String displayName,
        String phone,
        String avatarUrl
) {
}
