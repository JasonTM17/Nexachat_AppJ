package com.nexachat.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code POST /feed/posts/{id}/comments}; {@code clientCommentId} is the idempotency key. */
public record CreateCommentRequest(
        @NotBlank(message = "body is required")
        @Size(max = 500, message = "body must be at most 500 characters")
        String body,

        @NotNull(message = "clientCommentId is required")
        UUID clientCommentId
) {
}
