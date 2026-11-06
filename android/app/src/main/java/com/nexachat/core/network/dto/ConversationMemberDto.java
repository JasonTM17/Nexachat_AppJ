package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ConversationMemberDto {
    @Nullable private final String userId;
    @Nullable private final String displayName;
    @Nullable private final String phone;
    @Nullable private final String avatarUrl;
    @Nullable private final String role;

    public ConversationMemberDto() {
        this(null, null, null, null, null);
    }

    public ConversationMemberDto(@Nullable String userId, @Nullable String displayName, @Nullable String phone, @Nullable String avatarUrl, @Nullable String role) {
        this.userId = userId;
        this.displayName = displayName;
        this.phone = phone;
        this.avatarUrl = avatarUrl;
        this.role = role;
    }

    public ConversationMemberDto(String userId, String displayName, String role) {
        this(userId, displayName, null, null, role);
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

    @Nullable public String getRole() {
        return role;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConversationMemberDto that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(phone, that.phone)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, displayName, phone, avatarUrl, role);
    }
}
