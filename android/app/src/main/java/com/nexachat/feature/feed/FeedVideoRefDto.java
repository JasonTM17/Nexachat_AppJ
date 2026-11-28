package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** One feed video as the server describes it; {@code url} is relative to the API base. */
public final class FeedVideoRefDto {
    @Nullable private final String url;
    @Nullable private final String mimeType;

    public FeedVideoRefDto() {
        this(null, null);
    }

    public FeedVideoRefDto(@Nullable String url, @Nullable String mimeType) {
        this.url = url;
        this.mimeType = mimeType;
    }

    @Nullable public String getUrl() { return url; }

    @Nullable public String getMimeType() { return mimeType; }
}
