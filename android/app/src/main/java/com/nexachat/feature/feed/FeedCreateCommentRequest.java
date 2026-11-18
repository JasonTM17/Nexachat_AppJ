package com.nexachat.feature.feed;

import com.squareup.moshi.Json;

/** {@code POST /feed/posts/{id}/comments} body; the client id makes a retry idempotent. */
public final class FeedCreateCommentRequest {
    @Json(name = "body") private final String body;
    @Json(name = "clientCommentId") private final String clientCommentId;

    public FeedCreateCommentRequest(String body, String clientCommentId) {
        this.body = body;
        this.clientCommentId = clientCommentId;
    }
}
