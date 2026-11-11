package com.nexachat.rag;

import com.nexachat.message.MessageCreatedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Clears cached answers when new text changes the conversation used for RAG.
 */
@Component
@Profile("!test")
public class MessageCreatedAskCacheListener {

    private final RagQuestionCache askCache;

    public MessageCreatedAskCacheListener(RagQuestionCache askCache) {
        this.askCache = askCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        if ("TEXT".equals(event.message().type())) {
            askCache.deleteAllForConversation(event.conversationId());
        }
    }
}
