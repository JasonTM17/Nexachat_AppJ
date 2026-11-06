package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AvatarResponse {
    private final String avatarUrl;

    public AvatarResponse() {
        this(null);
    }

    public AvatarResponse(@NonNull String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    @NonNull public String getAvatarUrl() {
        return avatarUrl;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AvatarResponse that)) {
            return false;
        }
        return Objects.equals(avatarUrl, that.avatarUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(avatarUrl);
    }
}
