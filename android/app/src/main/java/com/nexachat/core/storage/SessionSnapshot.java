package com.nexachat.core.storage;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Durable session revision and lifecycle state stored alongside credentials. */
public final class SessionSnapshot {
    public enum Lifecycle {
        ACTIVE,
        CLOSING,
        CLOSED
    }

    private final long epoch;
    @NonNull private final Lifecycle lifecycle;

    public SessionSnapshot(long epoch, @NonNull Lifecycle lifecycle) {
        this.epoch = epoch;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public long getEpoch() {
        return epoch;
    }

    @NonNull
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public boolean admitsRefresh() {
        return lifecycle == Lifecycle.ACTIVE;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SessionSnapshot snapshot)) {
            return false;
        }
        return epoch == snapshot.epoch && lifecycle == snapshot.lifecycle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, lifecycle);
    }
}
