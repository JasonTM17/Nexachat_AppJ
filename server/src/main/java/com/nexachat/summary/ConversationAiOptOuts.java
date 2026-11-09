package com.nexachat.summary;

import java.util.Set;
import java.util.UUID;

/**
 * Read boundary for the per-conversation AI opt-out state. A row means the
 * member asked that their own messages in that conversation never be sent to
 * an external AI provider. The state is per member and per conversation; it
 * never exposes which member opted out to another member.
 */
public interface ConversationAiOptOuts {

    /** Members who opted out of external AI processing in this conversation. */
    Set<UUID> optedOutUserIds(UUID conversationId);

    /** Conversations where this member opted out, for caller-scoped reads. */
    Set<UUID> optedOutConversationIds(UUID userId);

    /** Whether the conversation has any eligible message from an opted-out author. */
    boolean hasOptedOutMessages(UUID conversationId);

    boolean isOptedOut(UUID conversationId, UUID userId);

    /** No-op implementation for unit tests and database-less contexts. */
    static ConversationAiOptOuts none() {
        return new ConversationAiOptOuts() {
            @Override
            public Set<UUID> optedOutUserIds(UUID conversationId) {
                return Set.of();
            }

            @Override
            public Set<UUID> optedOutConversationIds(UUID userId) {
                return Set.of();
            }

            @Override
            public boolean hasOptedOutMessages(UUID conversationId) {
                return false;
            }

            @Override
            public boolean isOptedOut(UUID conversationId, UUID userId) {
                return false;
            }
        };
    }
}
