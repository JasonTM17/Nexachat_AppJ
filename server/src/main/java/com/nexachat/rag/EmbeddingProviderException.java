package com.nexachat.rag;

/** Embedding provider failure. The message never contains the input text or the API key. */
public class EmbeddingProviderException extends RuntimeException {

    public EmbeddingProviderException(String message) {
        super(message);
    }

    public EmbeddingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
