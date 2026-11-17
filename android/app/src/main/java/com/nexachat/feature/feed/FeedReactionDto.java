package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** One emoji tally on a post; {@code mine} marks the caller's own reaction. */
public final class FeedReactionDto {
    @Nullable private final String emoji;
    @Nullable private final Integer count;
    @Nullable private final Boolean mine;

    public FeedReactionDto() {
        this(null, null, null);
    }

    public FeedReactionDto(
            @Nullable String emoji, @Nullable Integer count, @Nullable Boolean mine) {
        this.emoji = emoji;
        this.count = count;
        this.mine = mine;
    }

    @Nullable public String getEmoji() { return emoji; }

    public int resolvedCount() { return count == null ? 0 : Math.max(0, count); }

    public boolean isMine() { return Boolean.TRUE.equals(mine); }
}
