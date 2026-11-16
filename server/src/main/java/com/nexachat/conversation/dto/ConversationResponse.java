package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Conversation detail as returned by POST /conversations/direct (and reused
 * by later conversation reads). {@code type} is the enum name, e.g. "DIRECT".
 * {@code aiOptOut} is the caller's own per-conversation AI opt-out state.
 */
public record ConversationResponse(
        UUID id,
        String type,
        List<ConversationMemberView> members,
        Instant createdAt,
        boolean aiOptOut,
        boolean e2eeEnabled,
        int disappearingSeconds
) {

    /** Pre-disappearing shape for callers that predate the timer column. */
    public ConversationResponse(UUID id, String type, List<ConversationMemberView> members,
                                Instant createdAt, boolean aiOptOut, boolean e2eeEnabled) {
        this(id, type, members, createdAt, aiOptOut, e2eeEnabled, 0);
    }
}
