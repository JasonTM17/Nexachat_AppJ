package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;
import com.squareup.moshi.Json;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ContactDto {
    @Json(name = "contactUserId") @Nullable private final String contactUserId;
    @Json(name = "userId") @Nullable private final String userId;
    @Nullable private final String phone;
    @Nullable private final String displayName;
    @Nullable private final String aliasName;
    @Nullable private final String avatarUrl;
    @Nullable private final String createdAt;
    @Nullable private final String email;
    @Nullable private final Boolean emailVerified;

    public ContactDto() {
        this(null, null, null, null, null, null, null, null, null);
    }

    public ContactDto(@Nullable String contactUserId, @Nullable String userId, @Nullable String phone, @Nullable String displayName, @Nullable String aliasName, @Nullable String avatarUrl, @Nullable String createdAt) {
        this(contactUserId, userId, phone, displayName, aliasName, avatarUrl, createdAt, null, null);
    }

    public ContactDto(@Nullable String contactUserId, @Nullable String userId, @Nullable String phone, @Nullable String displayName, @Nullable String aliasName, @Nullable String avatarUrl, @Nullable String createdAt, @Nullable String email, @Nullable Boolean emailVerified) {
        this.contactUserId = contactUserId;
        this.userId = userId;
        this.phone = phone;
        this.displayName = displayName;
        this.aliasName = aliasName;
        this.avatarUrl = avatarUrl;
        this.createdAt = createdAt;
        this.email = email;
        this.emailVerified = emailVerified;
    }

    @Nullable public String getContactUserId() {
        return contactUserId;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Nullable public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getAliasName() {
        return aliasName;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }

    @Nullable public String getEmail() {
        return email;
    }

    @Nullable public Boolean getEmailVerified() {
        return emailVerified;
    }

    @Nullable
    public String getResolvedUserId() {
        return contactUserId != null ? contactUserId : userId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ContactDto that)) {
            return false;
        }
        return Objects.equals(contactUserId, that.contactUserId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(phone, that.phone)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(aliasName, that.aliasName)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(email, that.email)
                && Objects.equals(emailVerified, that.emailVerified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                contactUserId, userId, phone, displayName, aliasName, avatarUrl, createdAt, email,
                emailVerified);
    }
}
