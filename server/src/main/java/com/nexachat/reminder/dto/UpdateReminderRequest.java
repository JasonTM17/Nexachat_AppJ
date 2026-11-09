package com.nexachat.reminder.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * {@code PATCH /api/v1/conversations/{id}/reminders/{reminderId}} body.
 * Every field is optional, but at least one must be present. A {@code null}
 * field means "keep the current value".
 */
public record UpdateReminderRequest(
        @Size(min = 1, max = 200) String title,
        Instant remindAt,
        @Size(max = 10) String repeat
) {
}
