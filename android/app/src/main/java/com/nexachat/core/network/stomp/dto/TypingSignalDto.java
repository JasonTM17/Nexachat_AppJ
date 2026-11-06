package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class TypingSignalDto {
    @Nullable private final String userId;
    @Nullable private final String displayName;

    public TypingSignalDto() {
        this(null, null);
    }

    public TypingSignalDto(@Nullable String userId, @Nullable String displayName) {
        this.userId = userId;
        this.displayName = displayName;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypingSignalDto that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, displayName);
    }
}
