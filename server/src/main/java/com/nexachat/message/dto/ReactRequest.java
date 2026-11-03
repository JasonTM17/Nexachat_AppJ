package com.nexachat.message.dto;

/**
 * {@code POST /messages/{id}/reactions} body: the emoji to toggle. Non-blank
 * and ≤ 16 characters (the {@code message_reactions.emoji} column width);
 * re-posting the same emoji removes the caller's reaction (toggle semantics,
 * plan §10.2).
 */
public record ReactRequest(String emoji) {
}
