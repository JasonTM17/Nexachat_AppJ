package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReactionRequest {
    private final String emoji;

    public ReactionRequest() {
        this(null);
    }

    public ReactionRequest(@NonNull String emoji) {
        this.emoji = emoji;
    }

    @NonNull public String getEmoji() {
        return emoji;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReactionRequest that)) {
            return false;
        }
        return Objects.equals(emoji, that.emoji);
    }

    @Override
    public int hashCode() {
        return Objects.hash(emoji);
    }
}
