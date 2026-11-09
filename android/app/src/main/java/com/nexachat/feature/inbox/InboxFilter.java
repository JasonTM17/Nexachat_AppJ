package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Filters available for the local inbox list. */
public enum InboxFilter {
    ALL,
    UNREAD,
    GROUPS,
    ARCHIVED;

    @NonNull
    public static InboxFilter fromSavedValue(@Nullable String value) {
        if (value == null) {
            return ALL;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
