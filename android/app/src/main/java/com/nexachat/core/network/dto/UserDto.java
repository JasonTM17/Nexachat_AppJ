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
    @Nullable private final String email;
    @Nullable private final Boolean emailVerified;

    public UserDto() {
        this(null, null, null, null, null, null);
    }

    public UserDto(@NonNull String id, @Nullable String phone, @NonNull String displayName, @Nullable String avatarUrl) {
        this(id, phone, displayName, avatarUrl, null, null);
    }

    public UserDto(
            @NonNull String id,
            @Nullable String phone,
            @NonNull String displayName,
            @Nullable String avatarUrl,
            @Nullable String email,
            @Nullable Boolean emailVerified) {
        this.id = id;
        this.phone = phone;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.email = email;
        this.emailVerified = emailVerified;
    }

    public UserDto(String id, String phone, String displayName) {
        this(id, phone, displayName, null);
    }

    @NonNull public String getId() {
        return id;
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @NonNull public String getDisplayName() {
        return displayName;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @Nullable
    public String getEmail() {
        return email;
    }

    @Nullable
    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public boolean hasVerifiedRecoveryEmail() {
        return email != null
                && !email.isEmpty()
                && Boolean.TRUE.equals(emailVerified);
    }

    public UserDto withDisplayName(String displayName) {
        return new UserDto(id, phone, displayName, avatarUrl, email, emailVerified);
    }

    public UserDto withAvatarUrl(String avatarUrl) {
        return new UserDto(id, phone, displayName, avatarUrl, email, emailVerified);
    }

    public UserDto withRecoveryEmail(@Nullable String nextEmail, @Nullable Boolean verified) {
        return new UserDto(id, phone, displayName, avatarUrl, nextEmail, verified);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UserDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(phone, that.phone)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(email, that.email)
                && Objects.equals(emailVerified, that.emailVerified);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, phone, displayName, avatarUrl, email, emailVerified);
    }
}
