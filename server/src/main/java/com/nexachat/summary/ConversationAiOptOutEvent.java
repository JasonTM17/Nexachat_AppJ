package com.nexachat.summary;

import java.util.UUID;

/**
 * Published after a member opts a conversation out of AI analysis. Downstream
 * listeners (alert snippet tombstoning, summary caches) react to the privacy
 * flip outside the write transaction.
 */
public record ConversationAiOptOutEvent(UUID conversationId, UUID userId) {
}
