package com.nexachat.feed.dto;

import java.util.List;

/** Newest-first keyset page of the public feed; {@code nextCursor} is opaque. */
public record PostPageResponse(List<PostResponse> items, String nextCursor) {
}
