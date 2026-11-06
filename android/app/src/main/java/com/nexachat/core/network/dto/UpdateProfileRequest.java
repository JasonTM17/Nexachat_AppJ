package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class UpdateProfileRequest {
    @Nullable private final String displayName;

    public UpdateProfileRequest() {
        this(null);
    }

    public UpdateProfileRequest(@Nullable String displayName) {
        this.displayName = displayName;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UpdateProfileRequest that)) {
            return false;
        }
        return Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayName);
    }
}
