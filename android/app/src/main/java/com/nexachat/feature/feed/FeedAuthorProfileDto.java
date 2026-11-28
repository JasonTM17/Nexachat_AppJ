package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** Public feed profile for an author visible to the caller. */
public final class FeedAuthorProfileDto {
    @Nullable private final String userId;
    @Nullable private final String displayName;
    @Nullable private final String avatarUrl;
    @Nullable private final Long postCount;

    public FeedAuthorProfileDto() {
        this(null, null, null, null);
    }

    public FeedAuthorProfileDto(
            @Nullable String userId,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable Long postCount) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.postCount = postCount;
    }

    @Nullable public String getUserId() { return userId; }

    @Nullable public String getDisplayName() { return displayName; }

    @Nullable public String getAvatarUrl() { return avatarUrl; }

    public long resolvedPostCount() { return postCount == null ? 0L : Math.max(0L, postCount); }
}
