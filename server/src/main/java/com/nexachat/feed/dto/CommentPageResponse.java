package com.nexachat.feed.dto;

import java.util.List;

/** Oldest-first keyset page of one post's comments; {@code nextCursor} is opaque. */
public record CommentPageResponse(List<CommentResponse> items, String nextCursor) {
}
