package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Recurrence rule of a conversation reminder, wire-compatible with the server enum. */
public enum ReminderRepeat {
    NONE,
    DAILY,
    WEEKLY,
    MONTHLY;

    @NonNull
    public static ReminderRepeat fromWire(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        for (ReminderRepeat repeat : values()) {
            if (repeat.name().equalsIgnoreCase(value.trim())) {
                return repeat;
            }
        }
        return NONE;
    }

    @NonNull
    public String wireName() {
        return name();
    }
}
