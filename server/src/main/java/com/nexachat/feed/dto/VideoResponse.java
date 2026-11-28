package com.nexachat.feed.dto;

/**
 * One video attached to a post. {@code url} is the feed-only serving path
 * ({@code /api/v1/feed/videos/{id}}); the underlying storage key is never
 * exposed.
 */
public record VideoResponse(String url, String mimeType) {
}
