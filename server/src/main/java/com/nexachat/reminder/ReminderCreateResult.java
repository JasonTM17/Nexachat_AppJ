package com.nexachat.reminder;

import com.nexachat.reminder.dto.ReminderResponse;

/**
 * Outcome of an idempotent create: {@code created} distinguishes a fresh
 * reminder (HTTP 201) from an {@code Idempotency-Key} replay (HTTP 200).
 */
public record ReminderCreateResult(ReminderResponse reminder, boolean created) {
}
