package com.nexachat.reminder;

import java.util.UUID;

/** Published in the reminder CRUD transaction so the scheduler can re-arm after commit. */
public record ReminderScheduleChangedEvent(UUID reminderId) {
}
