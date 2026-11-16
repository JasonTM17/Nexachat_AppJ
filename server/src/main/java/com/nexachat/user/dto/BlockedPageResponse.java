package com.nexachat.user.dto;

import java.util.List;

/**
 * Keyset page of {@link BlockedUserView}; {@code nextCursor} is the opaque
 * token for the following page, {@code null} at the end.
 */
public record BlockedPageResponse(List<BlockedUserView> items, String nextCursor) {
}
