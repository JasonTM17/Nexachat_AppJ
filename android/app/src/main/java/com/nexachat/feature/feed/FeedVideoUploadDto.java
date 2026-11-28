package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** Result of {@code POST /feed/videos}: an author-owned, still unbound MP4 upload. */
public final class FeedVideoUploadDto {
    @Nullable private final String videoId;
    @Nullable private final String url;
    @Nullable private final String mimeType;

    public FeedVideoUploadDto() {
        this(null, null, null);
    }

    public FeedVideoUploadDto(
            @Nullable String videoId, @Nullable String url, @Nullable String mimeType) {
        this.videoId = videoId;
        this.url = url;
        this.mimeType = mimeType;
    }

    @Nullable public String getVideoId() { return videoId; }

    @Nullable public String getUrl() { return url; }

    @Nullable public String getMimeType() { return mimeType; }
}
