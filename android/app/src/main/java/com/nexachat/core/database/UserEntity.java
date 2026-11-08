package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/**
 * Persisted user profile cached locally for offline contact and presence resolution.
 */
@Entity(
        tableName = "users",
        indices = {
            @Index(value = {"displayName"}, name = "index_users_displayName")
        })
public final class UserEntity {
    @PrimaryKey @NonNull private final String id;
    @Nullable private final String username;
    @Nullable private final String displayName;
    @Nullable private final String phoneNumber;
    @Nullable private final String avatarUrl;
    @Nullable private final String bio;
    @Nullable private final Long lastSeenAt;
    @Nullable private final Long updatedAt;

    public UserEntity(
            @NonNull String id,
            @Nullable String username,
            @Nullable String displayName,
            @Nullable String phoneNumber,
            @Nullable String avatarUrl,
            @Nullable String bio,
            @Nullable Long lastSeenAt,
            @Nullable Long updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = username;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.avatarUrl = avatarUrl;
        this.bio = bio;
        this.lastSeenAt = lastSeenAt;
        this.updatedAt = updatedAt;
    }

    @NonNull public String getId() { return id; }
    @Nullable public String getUsername() { return username; }
    @Nullable public String getDisplayName() { return displayName; }
    @Nullable public String getPhoneNumber() { return phoneNumber; }
    @Nullable public String getAvatarUrl() { return avatarUrl; }
    @Nullable public String getBio() { return bio; }
    @Nullable public Long getLastSeenAt() { return lastSeenAt; }
    @Nullable public Long getUpdatedAt() { return updatedAt; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserEntity)) return false;
        UserEntity that = (UserEntity) other;
        return Objects.equals(id, that.id)
                && Objects.equals(username, that.username)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(phoneNumber, that.phoneNumber)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(bio, that.bio)
                && Objects.equals(lastSeenAt, that.lastSeenAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override public int hashCode() {
        return Objects.hash(id, username, displayName, phoneNumber, avatarUrl, bio, lastSeenAt, updatedAt);
    }

    @NonNull @Override public String toString() {
        return "UserEntity(id=" + id + ", username=" + username + ", displayName=" + displayName
                + ", phoneNumber=" + phoneNumber + ", avatarUrl=" + avatarUrl + ", bio=" + bio
                + ", lastSeenAt=" + lastSeenAt + ", updatedAt=" + updatedAt + ')';
    }
}
