package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class OtherUserDto {
    @Nullable private final String userId;
    @Nullable private final String displayName;
    @Nullable private final String avatarUrl;

    public OtherUserDto() {
        this(null, null, null);
    }

    public OtherUserDto(@Nullable String userId, @Nullable String displayName, @Nullable String avatarUrl) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OtherUserDto that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(avatarUrl, that.avatarUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, displayName, avatarUrl);
    }
}
