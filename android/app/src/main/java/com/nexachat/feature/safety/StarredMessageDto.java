package com.nexachat.feature.safety;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.MessageDto;

/** One starred-list row: the message plus the caller's star timestamp. */
public final class StarredMessageDto {
    @Nullable private final String starredAt;
    @Nullable private final MessageDto message;

    public StarredMessageDto() {
        this(null, null);
    }

    public StarredMessageDto(@Nullable String starredAt, @Nullable MessageDto message) {
        this.starredAt = starredAt;
        this.message = message;
    }

    @Nullable public String getStarredAt() {
        return starredAt;
    }

    @Nullable public MessageDto getMessage() {
        return message;
    }
}
