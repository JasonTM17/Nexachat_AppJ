package com.nexachat.feature.feed;

import com.squareup.moshi.Json;

/** {@code PATCH /feed/posts/{id}} body. */
public final class FeedEditPostRequest {
    @Json(name = "body") private final String body;

    public FeedEditPostRequest(String body) {
        this.body = body;
    }
}
