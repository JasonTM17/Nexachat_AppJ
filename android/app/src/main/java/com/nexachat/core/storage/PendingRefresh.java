package com.nexachat.core.storage;

import androidx.annotation.NonNull;
import java.util.Objects;

/** One durable in-flight refresh rotation bound to a session epoch and request key. */
public final class PendingRefresh {
    @NonNull private final String requestKey;
    @NonNull private final String predecessorRefreshToken;
    private final long sessionEpoch;

    public PendingRefresh(
            @NonNull String requestKey,
            @NonNull String predecessorRefreshToken,
            long sessionEpoch) {
        this.requestKey = Objects.requireNonNull(requestKey, "requestKey");
        this.predecessorRefreshToken = Objects.requireNonNull(
                predecessorRefreshToken, "predecessorRefreshToken");
        this.sessionEpoch = sessionEpoch;
    }

    @NonNull
    public String getRequestKey() {
        return requestKey;
    }

    @NonNull
    public String getPredecessorRefreshToken() {
        return predecessorRefreshToken;
    }

    public long getSessionEpoch() {
        return sessionEpoch;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PendingRefresh pending)) {
            return false;
        }
        return sessionEpoch == pending.sessionEpoch
                && requestKey.equals(pending.requestKey)
                && predecessorRefreshToken.equals(pending.predecessorRefreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestKey, predecessorRefreshToken, sessionEpoch);
    }
}
