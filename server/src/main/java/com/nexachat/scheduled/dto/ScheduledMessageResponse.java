package com.nexachat.scheduled.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of one scheduled_messages row. {@code body} is the scheduled
 * text while the row is PENDING and {@code ""} afterwards (terminal
 * transitions scrub it); a SENT row's content lives in the message
 * {@code sentMessageId} points at. {@code failureCode} is only set on FAILED
 * and on an administrative CANCELED.
 */
public record ScheduledMessageResponse(
        UUID id,
        UUID conversationId,
        UUID clientMessageId,
        String body,
        Instant sendAt,
        String status,
        String failureCode,
        UUID sentMessageId,
        Instant createdAt,
        Instant updatedAt
) {
}
