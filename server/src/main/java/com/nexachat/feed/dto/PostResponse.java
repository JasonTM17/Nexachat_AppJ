package com.nexachat.feed.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One feed post as the caller sees it. {@code mine} marks the caller's own
 * post (the client offers delete only there); {@code reactions} is ordered by
 * emoji so the wire shape is stable.
 */
public record PostResponse(
        UUID id,
        AuthorResponse author,
        String body,
        ImageResponse image,
        Instant createdAt,
        int commentCount,
        List<ReactionSummary> reactions,
        boolean mine
) {
}
