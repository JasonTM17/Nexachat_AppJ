package com.nexachat.feed.dto;

/**
 * One image attached to a post. {@code url} is the feed-only serving path
 * ({@code /api/v1/feed/images/{id}}); the underlying storage key is never
 * exposed.
 */
public record ImageResponse(String url, Integer width, Integer height, String mimeType) {
}
