package com.nexachat.feed.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /feed/posts}. {@code clientPostId} is the idempotency key: a
 * retried create returns the row the first attempt stored. {@code body} may be
 * absent only when {@code imageId} is present; a {@code videoId} post always
 * needs text and cannot also carry an image, so older clients without video
 * support still see a useful caption.
 */
public record CreatePostRequest(
        @Size(max = 1000, message = "body must be at most 1000 characters")
        String body,

        UUID imageId,

        @NotNull(message = "clientPostId is required")
        UUID clientPostId,

        UUID videoId
) {
    public CreatePostRequest(String body, UUID imageId, UUID clientPostId) {
        this(body, imageId, clientPostId, null);
    }
}
