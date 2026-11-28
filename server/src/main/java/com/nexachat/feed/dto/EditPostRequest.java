package com.nexachat.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code PATCH /feed/posts/{postId}} and {@code PATCH /social/v1/posts/{postId}}. */
public record EditPostRequest(
        @NotBlank(message = "body is required")
        @Size(max = 1000, message = "body must be at most 1000 characters")
        String body
) {
}
