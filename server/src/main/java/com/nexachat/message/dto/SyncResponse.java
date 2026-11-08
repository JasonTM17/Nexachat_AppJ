package com.nexachat.message.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncResponse(
        List<ConversationDelta> deltas,
        Instant serverTime
) {
    public record ConversationDelta(
            UUID conversationId,
            List<MessageResponse> newMessages,
            List<MessageResponse> editedMessages,
            List<UUID> deletedMessageIds,
            List<ReactionUpdatedPayload> reactionUpdates,
            Long maxSeq,
            boolean hasMore
    ) {
    }
}
