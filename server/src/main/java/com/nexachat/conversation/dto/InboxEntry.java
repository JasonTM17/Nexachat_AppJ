package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code GET /conversations} — the inbox spec shape: members for
 * title/avatar derivation, denormalized {@code lastMessage} preview (no extra
 * query), and the caller's unread count + watermark.
 */
public record InboxEntry(
        UUID id,
        String type,
        String title,
        List<Member> members,
        LastMessage lastMessage,
        long unreadCount,
        long lastReadSeq
) {

    /** Member as the client needs it for headers/avatars plus admin gating. */
    public record Member(
            UUID userId,
            String displayName,
            String phone,
            String avatarUrl,
            String role
    ) {
    }

    /** Preview of the newest message; {@code null} for a conversation with no messages. */
    public record LastMessage(
            Long seq,
            String type,
            String contentSnippet,
            UUID senderId,
            Instant createdAt
    ) {
    }
}
