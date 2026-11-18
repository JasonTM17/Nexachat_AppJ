package com.nexachat.feature.feed;

import androidx.annotation.Nullable;

/** One flat comment on a post. */
public final class FeedCommentDto {
    @Nullable private final String id;
    @Nullable private final String postId;
    @Nullable private final FeedAuthorDto author;
    @Nullable private final String body;
    @Nullable private final String createdAt;
    @Nullable private final Boolean mine;

    public FeedCommentDto() {
        this(null, null, null, null, null, null);
    }

    public FeedCommentDto(
            @Nullable String id,
            @Nullable String postId,
            @Nullable FeedAuthorDto author,
            @Nullable String body,
            @Nullable String createdAt,
            @Nullable Boolean mine) {
        this.id = id;
        this.postId = postId;
        this.author = author;
        this.body = body;
        this.createdAt = createdAt;
        this.mine = mine;
    }

    @Nullable public String getId() { return id; }

    @Nullable public String getPostId() { return postId; }

    @Nullable public FeedAuthorDto getAuthor() { return author; }

    @Nullable public String getBody() { return body; }

    @Nullable public String getCreatedAt() { return createdAt; }

    public boolean isMine() { return Boolean.TRUE.equals(mine); }
}
