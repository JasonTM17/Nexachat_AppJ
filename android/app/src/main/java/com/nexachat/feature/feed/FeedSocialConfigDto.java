package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** Social runtime flags from {@code GET /api/social/v1/config}. */
public final class FeedSocialConfigDto {
    @Nullable private final Boolean webEnabled;
    @Nullable private final Boolean videoUploadsEnabled;

    public FeedSocialConfigDto() {
        this(null, null);
    }

    public FeedSocialConfigDto(
            @Nullable Boolean webEnabled, @Nullable Boolean videoUploadsEnabled) {
        this.webEnabled = webEnabled;
        this.videoUploadsEnabled = videoUploadsEnabled;
    }

    public boolean isWebEnabled() { return Boolean.TRUE.equals(webEnabled); }

    public boolean isVideoUploadsEnabled() { return Boolean.TRUE.equals(videoUploadsEnabled); }
}
