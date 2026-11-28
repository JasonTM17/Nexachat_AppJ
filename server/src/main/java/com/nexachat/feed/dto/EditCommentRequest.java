package com.nexachat.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code PATCH /feed/comments/{commentId}} and {@code PATCH /social/v1/comments/{commentId}}. */
public record EditCommentRequest(
        @NotBlank(message = "body is required")
        @Size(max = 500, message = "body must be at most 500 characters")
        String body
) {
}
