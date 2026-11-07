package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Group member projection. Role stays nullable because early server payloads
 * may omit it; a missing role hides admin affordances while the server remains
 * the authorization boundary.
 */
public final class GroupMember {

    public static final String ROLE_ADMIN = "ADMIN";

    private final String userId;
    private final String displayName;
    @Nullable private final String phone;
    @Nullable private final String avatarUrl;
    @Nullable private final String role;

    public GroupMember(
            String userId,
            String displayName,
            @Nullable String phone,
            @Nullable String avatarUrl,
            @Nullable String role) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.phone = phone;
        this.avatarUrl = avatarUrl;
        this.role = role;
    }

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    @Nullable public String getPhone() { return phone; }
    @Nullable public String getAvatarUrl() { return avatarUrl; }
    @Nullable public String getRole() { return role; }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GroupMember that)) return false;
        return userId.equals(that.userId)
                && displayName.equals(that.displayName)
                && Objects.equals(phone, that.phone)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, displayName, phone, avatarUrl, role);
    }
}
