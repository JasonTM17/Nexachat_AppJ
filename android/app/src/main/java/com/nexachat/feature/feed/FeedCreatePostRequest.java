package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import com.squareup.moshi.Json;

/**
 * {@code POST /feed/posts} body. {@code clientPostId} makes a retry idempotent: the server
 * returns the row it already stored for the same author and id.
 */
public final class FeedCreatePostRequest {
    @Json(name = "body") @Nullable private final String body;
    @Json(name = "imageId") @Nullable private final String imageId;
    @Json(name = "clientPostId") private final String clientPostId;

    public FeedCreatePostRequest(
            @Nullable String body, @Nullable String imageId, String clientPostId) {
        this.body = body;
        this.imageId = imageId;
        this.clientPostId = clientPostId;
    }
}
