package com.nexachat.rag;

import com.nexachat.message.MessageDeletedEvent;
import com.nexachat.message.dto.MessageDeletedPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageRecallAskCacheListenerTest {

    @Test
    void recallPurgesAskCacheAndEveryStoredEmbeddingModelForTheMessage() {
        RagQuestionCache askCache = mock(RagQuestionCache.class);
        RagMessageIndex messageIndex = mock(RagMessageIndex.class);
        MessageRecallAskCacheListener listener = new MessageRecallAskCacheListener(askCache, messageIndex);
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        listener.onMessageRecalled(new MessageDeletedEvent(
                conversationId, new MessageDeletedPayload(messageId, Instant.now())));

        verify(askCache).deleteAllForConversation(conversationId);
        verify(messageIndex).deleteEmbeddingsForMessage(messageId);
    }
}
