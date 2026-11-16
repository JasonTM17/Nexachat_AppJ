package com.nexachat.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/users/blocked}: the blocked user's public
 * profile fields plus when the block started.
 */
public record BlockedUserView(UUID userId, String displayName, String phone,
                              String avatarUrl, Instant blockedAt) {
}
