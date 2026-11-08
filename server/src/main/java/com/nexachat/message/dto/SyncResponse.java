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
            boolean hasMore,
            /**
             * Additive: the last {@code reaction_events.id} included in this
             * delta when the reaction page was truncated ({@code hasMore=true}).
             * Echo it as the watermark's {@code reactionCursor} on the next
             * request so the follow-up page cannot skip rows. {@code null} when
             * the page was not truncated.
             */
            Long reactionCursor,
            /**
             * Additive: {@code true} when the request's {@code since} is older
             * than the reaction-event retention window, so the reaction events
             * needed for a partial delta may no longer exist. Clients must do a
             * full reaction refresh (history reload) instead of applying the
             * empty {@code reactionUpdates}.
             */
            boolean reactionsFullRefresh
    ) {
    }
}
