package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of {@code GET /conversations} — the inbox spec shape: members for
 * title/avatar derivation, denormalized {@code lastMessage} preview (no extra
 * query), and the caller's unread count + watermark.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboxEntry(
        UUID id,
        String type,
        String title,
        String avatarUrl,
        List<Member> members,
        LastMessage lastMessage,
        long unreadCount,
        long lastReadSeq,
        PeerPresence peerPresence
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
            @JsonInclude(JsonInclude.Include.ALWAYS)
            String contentSnippet,
            UUID senderId,
            Instant createdAt,
            Instant deletedAt
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PeerPresence(boolean online, Instant lastSeenAt) {
    }
}
