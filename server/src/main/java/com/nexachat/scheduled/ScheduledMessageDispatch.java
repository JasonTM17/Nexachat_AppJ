package com.nexachat.scheduled;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a claimed scheduled message, used by the scheduler
 * between the claim transaction and the send+mark transaction — the two never
 * overlap, so the worker needs everything the dispatch needs up front.
 */
public record ScheduledMessageDispatch(
        UUID id,
        UUID conversationId,
        UUID senderId,
        UUID clientMessageId,
        String body,
        Instant sendAt,
        int attemptCount,
        UUID claimToken
) {
}
