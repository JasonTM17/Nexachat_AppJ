package com.nexachat.rag;

import com.nexachat.message.MessageDeletedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Purges ask-cache rows in the same transaction as message recall so a stale
 * answer cannot replay uncited context from a deleted message.
 */
@Component
@Profile("!test")
public class MessageRecallAskCacheListener {

    private final RagQuestionCache askCache;

    public MessageRecallAskCacheListener(RagQuestionCache askCache) {
        this.askCache = askCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageRecalled(MessageDeletedEvent event) {
        askCache.deleteAllForConversation(event.conversationId());
    }
}
