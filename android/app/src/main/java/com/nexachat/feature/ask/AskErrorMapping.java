package com.nexachat.feature.ask;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Safe error category and optional server retry delay. */
public final class AskErrorMapping {
    private final AskError error;
    @Nullable private final Long retryAfterSeconds;

    AskErrorMapping(AskError error, @Nullable Long retryAfterSeconds) {
        this.error = Objects.requireNonNull(error, "error");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public AskError getError() {
        return error;
    }

    @Nullable
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
