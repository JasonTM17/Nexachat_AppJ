package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class UserDto {
    private final String id;
    private final String phone;
    private final String displayName;
    @Nullable private final String avatarUrl;

    public UserDto() {
        this(null, null, null, null);
    }

    public UserDto(@NonNull String id, @NonNull String phone, @NonNull String displayName, @Nullable String avatarUrl) {
        this.id = id;
        this.phone = phone;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    public UserDto(String id, String phone, String displayName) {
        this(id, phone, displayName, null);
    }

    @NonNull public String getId() {
        return id;
    }

    @NonNull public String getPhone() {
        return phone;
    }

    @NonNull public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }


    public UserDto withDisplayName(String displayName) {
        return new UserDto(id, phone, displayName, avatarUrl);
    }

    public UserDto withAvatarUrl(String avatarUrl) {
        return new UserDto(id, phone, displayName, avatarUrl);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UserDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(phone, that.phone)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(avatarUrl, that.avatarUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, phone, displayName, avatarUrl);
    }
}
