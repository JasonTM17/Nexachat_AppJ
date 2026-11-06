package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class PeerPresenceDto {
    @Nullable private final Boolean online;
    @Nullable private final String lastSeenAt;

    public PeerPresenceDto() {
        this(null, null);
    }

    public PeerPresenceDto(@Nullable Boolean online, @Nullable String lastSeenAt) {
        this.online = online;
        this.lastSeenAt = lastSeenAt;
    }

    @Nullable public Boolean getOnline() {
        return online;
    }

    @Nullable public String getLastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PeerPresenceDto that)) {
            return false;
        }
        return Objects.equals(online, that.online)
                && Objects.equals(lastSeenAt, that.lastSeenAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(online, lastSeenAt);
    }
}
