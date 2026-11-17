package com.nexachat.feed.dto;

import java.util.UUID;

/**
 * The only author identity the feed exposes: id, display name and avatar.
 * Phone and email never leave the account module.
 */
public record AuthorResponse(UUID id, String displayName, String avatarUrl) {
}
