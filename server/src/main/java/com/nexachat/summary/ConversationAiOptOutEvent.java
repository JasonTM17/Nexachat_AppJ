package com.nexachat.summary;

import java.util.Set;
import java.util.UUID;

/**
 * Published after a member opts a conversation out of AI analysis. Downstream
 * listeners (alert snippet tombstoning, summary caches, the in-memory vector
 * cache) react to the privacy flip outside the write transaction.
 *
 * @param messageIds the opted-out member's message ids in the conversation —
 *                   exactly the set whose derived data (stored embeddings,
 *                   cached vectors) the opt-out purges
 */
public record ConversationAiOptOutEvent(UUID conversationId, UUID userId, Set<UUID> messageIds) {
}
