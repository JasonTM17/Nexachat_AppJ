package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class PinUpdateDto {
    @Nullable private final String messageId;
    @Nullable private final Boolean pinned;

    public PinUpdateDto() {
        this(null, null);
    }

    public PinUpdateDto(@Nullable String messageId, @Nullable Boolean pinned) {
        this.messageId = messageId;
        this.pinned = pinned;
    }

    @Nullable public String getMessageId() {
        return messageId;
    }

    @Nullable public Boolean getPinned() {
        return pinned;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PinUpdateDto that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(pinned, that.pinned);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, pinned);
    }
}
