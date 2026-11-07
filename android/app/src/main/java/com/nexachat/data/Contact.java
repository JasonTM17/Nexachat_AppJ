package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Contact as the feature layer sees it. The wire DTO tolerates multiple user
 * id spellings and nullable fields; this domain shape is safe for UI code.
 */
public final class Contact {

    private final String userId;
    private final String phone;
    private final String displayName;
    @Nullable private final String aliasName;
    @Nullable private final String avatarUrl;
    @Nullable private final String createdAt;

    public Contact(
            String userId,
            String phone,
            String displayName,
            @Nullable String aliasName,
            @Nullable String avatarUrl,
            @Nullable String createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.phone = Objects.requireNonNull(phone, "phone");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.aliasName = aliasName;
        this.avatarUrl = avatarUrl;
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public String getPhone() {
        return phone;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public String getAliasName() {
        return aliasName;
    }

    @Nullable
    public String getAvatarUrl() {
        return avatarUrl;
    }

    @Nullable
    public String getCreatedAt() {
        return createdAt;
    }

    /** Label shown in lists: the owner alias beats the profile name when non-blank. */
    public String getPrimaryLabel() {
        return aliasName != null && !isBlank(aliasName) ? aliasName : displayName;
    }

    private boolean isBlank(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Contact contact)) return false;
        return userId.equals(contact.userId)
                && phone.equals(contact.phone)
                && displayName.equals(contact.displayName)
                && Objects.equals(aliasName, contact.aliasName)
                && Objects.equals(avatarUrl, contact.avatarUrl)
                && Objects.equals(createdAt, contact.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, phone, displayName, aliasName, avatarUrl, createdAt);
    }

    @Override
    public String toString() {
        return "Contact(userId=" + userId + ", phone=" + phone + ", displayName=" + displayName
                + ", aliasName=" + aliasName + ", avatarUrl=" + avatarUrl
                + ", createdAt=" + createdAt + ")";
    }
}
