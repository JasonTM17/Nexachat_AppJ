package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * Body of {@code PUT /api/v1/conversations/{id}/mute}. Exactly one of
 * {@code mutedUntil} (ISO-8601 UTC instant) or {@code forever: true} is sent;
 * Moshi omits a null field.
 */
public final class MuteRequest {
    @Nullable private final String mutedUntil;
    @Nullable private final Boolean forever;

    public MuteRequest(@Nullable String mutedUntil, @Nullable Boolean forever) {
        this.mutedUntil = mutedUntil;
        this.forever = forever;
    }

    /** Mutes until the given instant, formatted as an ISO-8601 UTC instant. */
    public static MuteRequest until(Instant instant) {
        return new MuteRequest(Objects.requireNonNull(instant, "instant").toString(), null);
    }

    /** Mutes until the caller turns notifications back on. */
    public static MuteRequest forever() {
        return new MuteRequest(null, Boolean.TRUE);
    }

    @Nullable public String getMutedUntil() {
        return mutedUntil;
    }

    @Nullable public Boolean getForever() {
        return forever;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MuteRequest that)) {
            return false;
        }
        return Objects.equals(mutedUntil, that.mutedUntil)
                && Objects.equals(forever, that.forever);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mutedUntil, forever);
    }

    @Override
    public String toString() {
        return "MuteRequest(mutedUntil=" + mutedUntil + ", forever=" + forever + ')';
    }
}
