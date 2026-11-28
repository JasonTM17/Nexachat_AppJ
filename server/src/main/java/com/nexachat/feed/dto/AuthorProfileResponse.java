package com.nexachat.feed.dto;

import java.util.UUID;

/** Public feed profile for an author visible to the caller. */
public record AuthorProfileResponse(
        UUID userId,
        String displayName,
        String avatarUrl,
        long postCount
) {
}
