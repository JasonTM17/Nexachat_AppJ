package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Conversation detail as returned by POST /conversations/direct (and reused
 * by later conversation reads). {@code type} is the enum name, e.g. "DIRECT".
 */
public record ConversationResponse(
        UUID id,
        String type,
        List<ConversationMemberView> members,
        Instant createdAt
) {
}
