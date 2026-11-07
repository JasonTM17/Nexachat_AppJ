package com.nexachat.data;

/** Aggregated emoji chip under a bubble. */
public record ReactionChip(String emoji, int count, boolean reactedByMe) {}
