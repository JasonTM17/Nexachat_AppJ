package com.nexachat.rag;

import com.nexachat.message.MessageUpdatedEvent;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Purges ask-cache rows and stored embeddings when message text changes so
 * stale uncited context and vectors cannot replay or match the old content.
 */
@Component
@Profile("!test")
public class MessageEditRagInvalidationListener {

    private final RagQuestionCache askCache;
    private final RagMessageIndex messageIndex;

    public MessageEditRagInvalidationListener(RagQuestionCache askCache, RagMessageIndex messageIndex) {
        this.askCache = askCache;
        this.messageIndex = messageIndex;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMessageEdited(MessageUpdatedEvent event) {
        UUID conversationId = event.conversationId();
        UUID messageId = event.message().id();
        askCache.deleteAllForConversation(conversationId);
        messageIndex.deleteEmbeddingsForMessage(messageId);
    }
}
