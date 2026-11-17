package com.nexachat.feed.dto;

import java.util.List;

/** {@code PUT}/{@code DELETE /feed/posts/{id}/reactions/{emoji}} — the post's aggregate. */
public record ReactionListResponse(List<ReactionSummary> reactions) {
}
