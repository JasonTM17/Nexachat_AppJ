package com.nexachat.feature.feed;

import com.squareup.moshi.Json;

/** {@code PATCH /feed/comments/{id}} body. */
public final class FeedEditCommentRequest {
    @Json(name = "body") private final String body;

    public FeedEditCommentRequest(String body) {
        this.body = body;
    }
}
