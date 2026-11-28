package com.nexachat.feed.dto;

import java.util.UUID;

/** {@code POST /feed/videos} — an unbound video upload the caller can attach to one post. */
public record FeedVideoResponse(UUID videoId, String url, String mimeType) {
}
