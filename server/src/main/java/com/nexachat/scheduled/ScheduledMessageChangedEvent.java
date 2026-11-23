package com.nexachat.scheduled;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside every transaction that creates a scheduled message or
 * moves it to another state. Two AFTER_COMMIT listeners consume it: the
 * scheduler re-arms its wake-up timer, and the broadcast listener pushes a
 * {@code SCHEDULED_MESSAGE_UPDATED} frame to the author's devices so all of
 * them converge without polling.
 */
public record ScheduledMessageChangedEvent(
        UUID scheduledId,
        UUID conversationId,
        UUID senderId,
        UUID clientMessageId,
        String status,
        String failureCode,
        Instant sendAt,
        UUID sentMessageId
) {
}
