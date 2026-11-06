package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class PinRequest {
    private final String messageId;

    public PinRequest() {
        this(null);
    }

    public PinRequest(@NonNull String messageId) {
        this.messageId = messageId;
    }

    @NonNull public String getMessageId() {
        return messageId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PinRequest that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }
}
