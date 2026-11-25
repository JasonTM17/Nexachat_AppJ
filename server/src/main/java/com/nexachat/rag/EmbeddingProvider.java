package com.nexachat.rag;

import java.util.List;

/** A text embedding model. Implementations must be deterministic for the same input. */
public interface EmbeddingProvider {

    /** Stable provider identifier; stored with every vector to scope reads. */
    String id();

    /** Vector length produced by this provider. */
    int dimension();

    float[] embed(String text);

    /** Timeout-bounded variant. Providers without a network call may ignore the budget. */
    default float[] embed(String text, long timeoutMs) {
        return embed(text);
    }

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** Timeout-bounded batch variant covering the whole list, not every element anew. */
    default List<float[]> embedAll(List<String> texts, long timeoutMs) {
        return texts.stream().map(text -> embed(text, timeoutMs)).toList();
    }
}
