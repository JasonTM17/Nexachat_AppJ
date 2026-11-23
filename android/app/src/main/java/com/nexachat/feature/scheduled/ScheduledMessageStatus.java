package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Server lifecycle of one scheduled message row. */
public enum ScheduledMessageStatus {
    PENDING,
    SENT,
    CANCELED,
    FAILED;

    /** Rows with an unknown or absent status are not renderable. */
    @Nullable
    public static ScheduledMessageStatus fromWire(@Nullable String wire) {
        if (wire == null) {
            return null;
        }
        switch (wire.trim()) {
            case "PENDING":
                return PENDING;
            case "SENT":
                return SENT;
            case "CANCELED":
                return CANCELED;
            case "FAILED":
                return FAILED;
            default:
                return null;
        }
    }

    /** Terminal rows cannot be cancelled; the server clears their body. */
    public boolean isTerminal() {
        return this != PENDING;
    }

    @NonNull
    public String wireName() {
        return name();
    }
}
