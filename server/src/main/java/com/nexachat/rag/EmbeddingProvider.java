package com.nexachat.rag;

import java.util.List;

/** A text embedding model. Implementations must be deterministic for the same input. */
public interface EmbeddingProvider {

    /** Stable provider identifier; stored with every vector to scope reads. */
    String id();

    /** Vector length produced by this provider. */
    int dimension();

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
