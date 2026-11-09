package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncResponse(
        List<ConversationDelta> deltas,
        Instant serverTime
) {
    /**
     * One conversation's delta. The trailing {@code muted}/{@code mutedUntil}/
     * {@code archived}/{@code pinned}/{@code pinnedAt} block is the
     * <b>caller's own</b> per-member preference state, carried on every delta
     * so a preference changed on one device reaches the others without a
     * separate inbox fetch. {@code mutedUntil} is omitted when the mute is
     * forever or the conversation is not muted; {@code pinnedAt} is omitted
     * while the conversation is not pinned.
     */
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
            boolean reactionsFullRefresh,
            boolean muted,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant mutedUntil,
            boolean archived,
            boolean pinned,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant pinnedAt
    ) {
    }
}
