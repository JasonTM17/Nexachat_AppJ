package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;

/** One row of the caller's blocked-user list. */
public final class BlockedUserDto {
    @Json(name = "userId") @Nullable private final String userId;
    @Nullable private final String displayName;
    @Nullable private final String phone;
    @Nullable private final String avatarUrl;
    @Nullable private final String blockedAt;

    public BlockedUserDto() {
        this(null, null, null, null, null);
    }

    public BlockedUserDto(
            @Nullable String userId,
            @Nullable String displayName,
            @Nullable String phone,
            @Nullable String avatarUrl,
            @Nullable String blockedAt) {
        this.userId = userId;
        this.displayName = displayName;
        this.phone = phone;
        this.avatarUrl = avatarUrl;
        this.blockedAt = blockedAt;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @Nullable public String getBlockedAt() {
        return blockedAt;
    }
}
