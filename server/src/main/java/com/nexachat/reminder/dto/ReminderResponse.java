package com.nexachat.reminder.dto;

import com.nexachat.reminder.ReminderRepeat;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of a reminder on the shared conversation board.
 *
 * <p>{@code canEdit} is computed for the calling member
 * ({@code createdBy == caller} or a GROUP admin) so clients do not duplicate
 * the authorization rule. {@code createdByName} is the creator's current
 * display name.</p>
 */
public record ReminderResponse(
        UUID id,
        UUID conversationId,
        String title,
        Instant remindAt,
        ReminderRepeat repeat,
        UUID createdBy,
        String createdByName,
        boolean canEdit,
        Instant createdAt,
        Instant updatedAt
) {
}
