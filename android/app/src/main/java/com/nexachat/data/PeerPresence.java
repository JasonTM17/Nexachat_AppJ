package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Peer presence as the feature layer sees it: online from the live
 * {@code USER_ONLINE}/{@code USER_OFFLINE} push, lastSeenAtEpochMs persisted
 * server-side on the last disconnect.
 */
public final class PeerPresence {

    private final boolean online;
    @Nullable private final Long lastSeenAtEpochMs;

    public PeerPresence(boolean online, @Nullable Long lastSeenAtEpochMs) {
        this.online = online;
        this.lastSeenAtEpochMs = lastSeenAtEpochMs;
    }

    public boolean getOnline() {
        return online;
    }

    /** Bean-style accessor for the current online state. */
    public boolean isOnline() {
        return online;
    }

    @Nullable
    public Long getLastSeenAtEpochMs() {
        return lastSeenAtEpochMs;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PeerPresence that)) {
            return false;
        }
        return online == that.online && Objects.equals(lastSeenAtEpochMs, that.lastSeenAtEpochMs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(online, lastSeenAtEpochMs);
    }

    @Override
    public String toString() {
        return "PeerPresence(online=" + online + ", lastSeenAtEpochMs=" + lastSeenAtEpochMs + ")";
    }
}
