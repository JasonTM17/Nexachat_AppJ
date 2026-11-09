package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Structured group-member mention range; offsets count UTF-16 code units. */
public final class MessageMention {
    @Nullable private final String userId;
    private final int offset;
    private final int length;

    public MessageMention() {
        this(null, 0, 0);
    }

    public MessageMention(@Nullable String userId, int offset, int length) {
        this.userId = userId;
        this.offset = offset;
        this.length = length;
    }

    @Nullable public String getUserId() { return userId; }
    public int getOffset() { return offset; }
    public int getLength() { return length; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof MessageMention that)) return false;
        return offset == that.offset && length == that.length
                && Objects.equals(userId, that.userId);
    }

    @Override public int hashCode() {
        return Objects.hash(userId, offset, length);
    }
}
