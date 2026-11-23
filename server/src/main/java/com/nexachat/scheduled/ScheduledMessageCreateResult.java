package com.nexachat.scheduled;

import com.nexachat.scheduled.dto.ScheduledMessageResponse;

/**
 * Result of the POST endpoint: {@code created=true} for a freshly stored row
 * (HTTP 201), {@code false} when the idempotency key replayed an existing
 * PENDING row (HTTP 200).
 */
public record ScheduledMessageCreateResult(ScheduledMessageResponse scheduledMessage,
                                           boolean created) {
}
