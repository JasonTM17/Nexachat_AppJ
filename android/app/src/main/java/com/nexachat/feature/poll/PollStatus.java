package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Server poll status; expiry is also evaluated locally for clock skew. */
public enum PollStatus {
    OPEN,
    CLOSED;

    @NonNull
    public static PollStatus fromWire(@Nullable String value) {
        return "CLOSED".equalsIgnoreCase(value) ? CLOSED : OPEN;
    }
}
