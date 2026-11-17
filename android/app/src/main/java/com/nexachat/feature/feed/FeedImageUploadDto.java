package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** Result of {@code POST /feed/images}: an author-owned, still unbound upload. */
public final class FeedImageUploadDto {
    @Nullable private final String imageId;
    @Nullable private final String url;
    @Nullable private final Integer width;
    @Nullable private final Integer height;
    @Nullable private final String mimeType;

    public FeedImageUploadDto() {
        this(null, null, null, null, null);
    }

    public FeedImageUploadDto(
            @Nullable String imageId,
            @Nullable String url,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable String mimeType) {
        this.imageId = imageId;
        this.url = url;
        this.width = width;
        this.height = height;
        this.mimeType = mimeType;
    }

    @Nullable public String getImageId() { return imageId; }

    @Nullable public String getUrl() { return url; }

    public int resolvedWidth() { return width == null ? 0 : Math.max(0, width); }

    public int resolvedHeight() { return height == null ? 0 : Math.max(0, height); }

    @Nullable public String getMimeType() { return mimeType; }
}
