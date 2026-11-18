package com.nexachat.feed.dto;

/** One emoji on a post: how many people used it and whether the caller did. */
public record ReactionSummary(String emoji, int count, boolean mine) {
}
