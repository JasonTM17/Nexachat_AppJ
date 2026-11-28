package com.nexachat.feed.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One feed post as the caller sees it. {@code mine} marks the caller's own
 * post (the client offers delete only there); {@code reactions} is ordered by
 * emoji so the wire shape is stable. {@code video} is present only on video
 * posts, so the wire shape of text and image posts is unchanged.
 */
public record PostResponse(
        UUID id,
        AuthorResponse author,
        String body,
        ImageResponse image,
        Instant createdAt,
        int commentCount,
        List<ReactionSummary> reactions,
        boolean mine,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        VideoResponse video
) {
}
