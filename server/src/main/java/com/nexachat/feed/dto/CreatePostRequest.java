package com.nexachat.feed.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /feed/posts}. {@code clientPostId} is the idempotency key: a
 * retried create returns the row the first attempt stored. {@code body} may be
 * absent only when {@code imageId} is present.
 */
public record CreatePostRequest(
        @Size(max = 1000, message = "body must be at most 1000 characters")
        String body,

        UUID imageId,

        @NotNull(message = "clientPostId is required")
        UUID clientPostId
) {
}
