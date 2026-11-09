package com.nexachat.reminder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * {@code POST /api/v1/conversations/{id}/reminders} body.
 *
 * <p>{@code repeat} is optional ({@code NONE} when absent). The service
 * validates that {@code remindAt} is in the future and no more than 365 days
 * ahead, because bean validation cannot use the application clock.</p>
 */
public record CreateReminderRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull Instant remindAt,
        @Size(max = 10) String repeat
) {
}
