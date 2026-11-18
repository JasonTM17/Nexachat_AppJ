package com.nexachat.feed.dto;

import java.time.Instant;
import java.util.UUID;

/** One flat comment under a post. */
public record CommentResponse(
        UUID id,
        UUID postId,
        AuthorResponse author,
        String body,
        Instant createdAt,
        boolean mine
) {
}
