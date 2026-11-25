package com.nexachat.rag;

import com.nexachat.message.MessageDeletedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Purges ask-cache rows and stored embeddings in the same transaction as
 * message recall so neither a stale answer nor derived vectors survive the
 * tombstone.
 */
@Component
@Profile("!test")
public class MessageRecallAskCacheListener {

    private final RagQuestionCache askCache;
    private final RagMessageIndex messageIndex;

    public MessageRecallAskCacheListener(RagQuestionCache askCache, RagMessageIndex messageIndex) {
        this.askCache = askCache;
        this.messageIndex = messageIndex;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageRecalled(MessageDeletedEvent event) {
        askCache.deleteAllForConversation(event.conversationId());
        messageIndex.deleteEmbeddingsForMessage(event.payload().messageId());
    }
}
