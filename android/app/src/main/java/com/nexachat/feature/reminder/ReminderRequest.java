package com.nexachat.feature.reminder;

import androidx.annotation.Nullable;

/** Reflective Moshi bean for create and patch reminder bodies; null fields are omitted. */
public final class ReminderRequest {
    @Nullable private final String title;
    @Nullable private final String remindAt;
    @Nullable private final String repeat;

    public ReminderRequest() {
        this(null, null, null);
    }

    public ReminderRequest(
            @Nullable String title, @Nullable String remindAt, @Nullable String repeat) {
        this.title = title;
        this.remindAt = remindAt;
        this.repeat = repeat;
    }

    @Nullable public String getTitle() { return title; }
    @Nullable public String getRemindAt() { return remindAt; }
    @Nullable public String getRepeat() { return repeat; }
}
