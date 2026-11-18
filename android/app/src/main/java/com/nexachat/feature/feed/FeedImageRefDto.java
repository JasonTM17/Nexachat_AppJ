package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** One feed image as the server describes it; {@code url} is relative to the API base. */
public final class FeedImageRefDto {
    @Nullable private final String url;
    @Nullable private final Integer width;
    @Nullable private final Integer height;
    @Nullable private final String mimeType;

    public FeedImageRefDto() {
        this(null, null, null, null);
    }

    public FeedImageRefDto(
            @Nullable String url,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable String mimeType) {
        this.url = url;
        this.width = width;
        this.height = height;
        this.mimeType = mimeType;
    }

    @Nullable public String getUrl() { return url; }

    public int resolvedWidth() { return width == null ? 0 : Math.max(0, width); }

    public int resolvedHeight() { return height == null ? 0 : Math.max(0, height); }

    @Nullable public String getMimeType() { return mimeType; }
}
