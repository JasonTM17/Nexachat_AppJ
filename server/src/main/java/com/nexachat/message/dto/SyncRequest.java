package com.nexachat.message.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncRequest(
        @NotEmpty(message = "watermarks list must not be empty")
        @Size(max = 100, message = "watermarks list must not exceed 100 items")
        @Valid
        List<@NotNull(message = "watermark must not be null") ConversationWatermark> watermarks,

        Integer limitPerConv,

        Instant since,

        @Min(1)
        @Max(2)
        Integer syncVersion
) {
    public SyncRequest(List<ConversationWatermark> watermarks, Integer limitPerConv, Instant since) {
        this(watermarks, limitPerConv, since, null);
    }

    public record ConversationWatermark(
            @NotNull(message = "conversationId is required")
            UUID conversationId,

            @NotNull(message = "lastSeq is required")
            Long lastSeq,

            /**
             * Additive reaction-delta cursor: the last {@code reaction_events.id}
             * the client received for this conversation. Echo the
             * {@code reactionCursor} of the previous delta response; omit it on
             * the first sync. It only matters when the previous response set
             * {@code hasMore} because the reaction page was truncated.
             */
            Long reactionCursor,

            @Min(0)
            Long changeCursor,

            @Min(1)
            Long generation
    ) {
        public ConversationWatermark(UUID conversationId, Long lastSeq, Long reactionCursor) {
            this(conversationId, lastSeq, reactionCursor, null, null);
        }

        /** Convenience for callers that do not page reaction deltas. */
        public ConversationWatermark(UUID conversationId, Long lastSeq) {
            this(conversationId, lastSeq, null, null, null);
        }
    }
}
