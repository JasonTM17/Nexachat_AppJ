package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReadRequest {
    private final long upToSeq;

    public ReadRequest() {
        this(0L);
    }

    public ReadRequest(long upToSeq) {
        this.upToSeq = upToSeq;
    }

    public long getUpToSeq() {
        return upToSeq;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReadRequest that)) {
            return false;
        }
        return upToSeq == that.upToSeq;
    }

    @Override
    public int hashCode() {
        return Objects.hash(upToSeq);
    }
}
