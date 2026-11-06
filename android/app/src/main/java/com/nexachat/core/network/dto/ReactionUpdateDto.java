package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReactionUpdateDto {
    @Nullable private final String messageId;
    @Nullable private final List<ReactionSummaryDto> reactions;

    public ReactionUpdateDto() {
        this(null, List.of());
    }

    public ReactionUpdateDto(@Nullable String messageId, @Nullable List<ReactionSummaryDto> reactions) {
        this.messageId = messageId;
        this.reactions = reactions == null ? List.of() : reactions;
    }

    @Nullable public String getMessageId() {
        return messageId;
    }

    @NonNull public List<ReactionSummaryDto> getReactions() {
        return reactions;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReactionUpdateDto that)) {
            return false;
        }
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(reactions, that.reactions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, reactions);
    }
}
