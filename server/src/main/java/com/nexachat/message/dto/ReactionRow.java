package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Flat projection of one {@code message_reactions} row — feeds the grouped
 * {@link ReactionView} / {@link ReactionSlice} assemblies without entity
 * loading (plan §9.3.7).
 */
public record ReactionRow(UUID messageId, UUID userId, String emoji) {
}
