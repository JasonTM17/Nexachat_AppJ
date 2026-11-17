package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** Public author identity on a feed post or comment: id, display name and avatar only. */
public final class FeedAuthorDto {
    @Nullable private final String id;
    @Nullable private final String displayName;
    @Nullable private final String avatarUrl;

    public FeedAuthorDto() {
        this(null, null, null);
    }

    public FeedAuthorDto(
            @Nullable String id, @Nullable String displayName, @Nullable String avatarUrl) {
        this.id = id;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    @Nullable public String getId() { return id; }

    @Nullable public String getDisplayName() { return displayName; }

    @Nullable public String getAvatarUrl() { return avatarUrl; }
}
