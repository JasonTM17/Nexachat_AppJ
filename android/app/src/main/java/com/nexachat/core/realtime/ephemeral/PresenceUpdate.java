package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.NonNull;
import com.nexachat.data.PeerPresence;
import androidx.annotation.Nullable;
import java.util.Objects;

/** A transport-neutral update for one direct peer. */
public final class PresenceUpdate {
    private final String userId;
    private final PeerPresence presence;
    @Nullable private final Long eventAtEpochMs;

    public PresenceUpdate(@NonNull String userId, @NonNull PeerPresence presence) {
        this(userId, presence, null);
    }

    public PresenceUpdate(
            @NonNull String userId,
            @NonNull PeerPresence presence,
            @Nullable Long eventAtEpochMs) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.eventAtEpochMs = eventAtEpochMs;
    }

    @NonNull public String getUserId() { return userId; }
    @NonNull public PeerPresence getPresence() { return presence; }
    @Nullable public Long getEventAtEpochMs() { return eventAtEpochMs; }
}
