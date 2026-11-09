package com.nexachat.reminder;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of a claimed reminder, used by the scheduler between the
 * claim transaction and the advance transaction.
 */
public record ReminderDelivery(
        UUID reminderId,
        UUID conversationId,
        String title,
        Instant remindAt,
        Instant anchorAt,
        ReminderRepeat repeat,
        UUID claimToken
) {
}
