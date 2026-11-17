package com.nexachat.feed.dto;

import java.util.UUID;

/** {@code POST /feed/images} — an unbound upload the caller can attach to one post. */
public record FeedImageResponse(UUID imageId, String url, Integer width, Integer height,
                                String mimeType) {
}
