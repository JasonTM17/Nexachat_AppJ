package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class SenderRefDto {
    @Nullable private final String id;
    @Nullable private final String displayName;

    public SenderRefDto() {
        this(null, null);
    }

    public SenderRefDto(@Nullable String id, @Nullable String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Nullable public String getId() {
        return id;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SenderRefDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName);
    }
}
