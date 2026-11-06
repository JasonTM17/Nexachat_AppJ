package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class PresenceEventDto {
    @Nullable private final String userId;
    @Nullable private final Boolean online;
    @Nullable private final String lastSeenAt;

    public PresenceEventDto() {
        this(null, null, null);
    }

    public PresenceEventDto(@Nullable String userId, @Nullable Boolean online, @Nullable String lastSeenAt) {
        this.userId = userId;
        this.online = online;
        this.lastSeenAt = lastSeenAt;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public Boolean getOnline() {
        return online;
    }

    @Nullable public String getLastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PresenceEventDto that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(online, that.online)
                && Objects.equals(lastSeenAt, that.lastSeenAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, online, lastSeenAt);
    }
}
