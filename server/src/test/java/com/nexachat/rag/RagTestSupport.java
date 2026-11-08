package com.nexachat.rag;

import com.nexachat.summary.llm.Redactor;

import java.util.UUID;

/** Construction helpers shared by RAG unit tests. */
final class RagTestSupport {

    private RagTestSupport() {
    }

    static RagRetrievalService retrieval(InMemoryRagMessageIndex index, RagProperties properties) {
        RagStats stats = new RagStats();
        RagEmbeddingProviders providers = new RagEmbeddingProviders(properties);
        RagEmbeddingService embeddings = new RagEmbeddingService(
                providers, index, new VectorCache(1024), properties, stats, new Redactor());
        return new RagRetrievalService(index, embeddings, properties);
    }

    static RagMessage message(UUID conversationId, UUID id, long seq, UUID senderId,
                              String senderName, String content) {
        return new RagMessage(id, conversationId, seq, senderId, senderName, content,
                java.time.Instant.now(), null, null);
    }
}
