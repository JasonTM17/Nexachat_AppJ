package com.nexachat.feature.feed;

import androidx.annotation.Nullable;
import java.util.List;

/** One public post. {@code mine} is the server's answer for the caller. */
public final class FeedPostDto {
    @Nullable private final String id;
    @Nullable private final FeedAuthorDto author;
    @Nullable private final String body;
    @Nullable private final FeedImageRefDto image;
    @Nullable private final String createdAt;
    @Nullable private final Integer commentCount;
    @Nullable private final List<FeedReactionDto> reactions;
    @Nullable private final Boolean mine;

    public FeedPostDto() {
        this(null, null, null, null, null, null, null, null);
    }

    public FeedPostDto(
            @Nullable String id,
            @Nullable FeedAuthorDto author,
            @Nullable String body,
            @Nullable FeedImageRefDto image,
            @Nullable String createdAt,
            @Nullable Integer commentCount,
            @Nullable List<FeedReactionDto> reactions,
            @Nullable Boolean mine) {
        this.id = id;
        this.author = author;
        this.body = body;
        this.image = image;
        this.createdAt = createdAt;
        this.commentCount = commentCount;
        this.reactions = reactions;
        this.mine = mine;
    }

    @Nullable public String getId() { return id; }

    @Nullable public FeedAuthorDto getAuthor() { return author; }

    @Nullable public String getBody() { return body; }

    @Nullable public FeedImageRefDto getImage() { return image; }

    @Nullable public String getCreatedAt() { return createdAt; }

    public int resolvedCommentCount() {
        return commentCount == null ? 0 : Math.max(0, commentCount);
    }

    public List<FeedReactionDto> getReactions() {
        return reactions == null ? List.of() : reactions;
    }

    public boolean isMine() { return Boolean.TRUE.equals(mine); }
}
