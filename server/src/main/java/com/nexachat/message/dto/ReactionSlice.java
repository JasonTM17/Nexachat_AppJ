package com.nexachat.message.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-emoji slice of the {@code REACTION_UPDATED} broadcast payload:
 * {@code {emoji, count, userIds}}. Unlike the viewer-relative
 * {@link ReactionView}, the full reactor id list is carried so EVERY
 * receiving client computes its own {@code byMe} — the topic frame has one
 * shape for the whole audience.
 */
public record ReactionSlice(String emoji, long count, List<UUID> userIds) {
}
