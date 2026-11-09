package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/** Normalized reminder payload used to create and edit reminders. */
public final class ReminderDraft {

    private final String title;
    private final Instant remindAt;
    private final ReminderRepeat repeat;

    public ReminderDraft(
            @NonNull String title, @NonNull Instant remindAt, @NonNull ReminderRepeat repeat) {
        this.title = Objects.requireNonNull(title, "title");
        this.remindAt = Objects.requireNonNull(remindAt, "remindAt");
        this.repeat = Objects.requireNonNull(repeat, "repeat");
    }

    @NonNull public String getTitle() { return title; }
    @NonNull public Instant getRemindAt() { return remindAt; }
    @NonNull public ReminderRepeat getRepeat() { return repeat; }

    @Nullable
    public String normalizedTitle() {
        String trimmed = title.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @NonNull
    public ReminderDraft normalized() {
        return new ReminderDraft(title.trim(), remindAt, repeat);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReminderDraft that)) {
            return false;
        }
        return title.equals(that.title)
                && remindAt.equals(that.remindAt)
                && repeat == that.repeat;
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, remindAt, repeat);
    }
}
