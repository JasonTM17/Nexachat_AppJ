package com.nexachat.rag;

/** Embedding provider failure. The message never contains the input text or the API key. */
public class EmbeddingProviderException extends RuntimeException {

    private final int statusCode;

    public EmbeddingProviderException(String message) {
        this(message, -1);
    }

    public EmbeddingProviderException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public EmbeddingProviderException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /** HTTP status when the provider returned one, otherwise {@code -1}. */
    public int statusCode() {
        return statusCode;
    }
}
