package com.nexachat.core.network.stomp.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class MessageDeletedDto {
    @Nullable private final String messageId;
    @Nullable private final String deletedAt;

    public MessageDeletedDto() {
        this(null, null);
    }

    public MessageDeletedDto(@Nullable String messageId, @Nullable String deletedAt) {
        this.messageId = messageId;
        this.deletedAt = deletedAt;
    }

    @Nullable public String getMessageId() {
        return messageId;
    }

    @Nullable public String getDeletedAt() {
        return deletedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MessageDeletedDto that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(deletedAt, that.deletedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, deletedAt);
    }
}
