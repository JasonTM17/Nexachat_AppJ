package com.nexachat.rag;

import com.nexachat.summary.ConversationAiOptOutEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;
import java.util.UUID;

/**
 * Purges the in-memory {@link VectorCache} when a member opts a conversation
 * out of AI processing. The opt-out transaction deletes the stored embedding
 * rows; this listener evicts the same messages' vectors from the cache so they
 * cannot keep influencing retrieval ranking until eviction or restart. The
 * cache is keyed by {@code (model, messageId)} and carries no conversation, so
 * eviction is message-scoped: the event carries the opted-out member's message
 * ids in the conversation, which is the conversation-scoped set the cache can
 * express.
 */
@Component
@Profile("!test")
public class ConversationAiOptOutVectorCacheListener {

    private final VectorCache vectorCache;

    public ConversationAiOptOutVectorCacheListener(VectorCache vectorCache) {
        this.vectorCache = vectorCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationOptOut(ConversationAiOptOutEvent event) {
        Set<UUID> messageIds = event.messageIds();
        if (messageIds != null && !messageIds.isEmpty()) {
            vectorCache.removeMessageIds(messageIds);
        }
    }
}
