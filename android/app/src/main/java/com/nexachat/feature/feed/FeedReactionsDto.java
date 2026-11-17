package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import java.util.List;

/** Reaction tally returned by the reaction endpoints. */
public final class FeedReactionsDto {
    @Nullable private final List<FeedReactionDto> reactions;

    public FeedReactionsDto() {
        this(null);
    }

    public FeedReactionsDto(@Nullable List<FeedReactionDto> reactions) {
        this.reactions = reactions;
    }

    public List<FeedReactionDto> getReactions() {
        return reactions == null ? List.of() : reactions;
    }
}
