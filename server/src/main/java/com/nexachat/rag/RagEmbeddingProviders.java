package com.nexachat.rag;

import org.springframework.stereotype.Component;

/**
 * Owns the embedding provider instances. The local provider is always present;
 * the external provider exists only when the operator configured a usable
 * base URL, model and key. An invalid external configuration degrades to the
 * local provider instead of failing application startup.
 */
@Component
public class RagEmbeddingProviders {

    private final LocalLexicalEmbedding local = new LocalLexicalEmbedding();
    private final OpenAiCompatibleEmbeddingProvider external;

    public RagEmbeddingProviders(RagProperties properties) {
        OpenAiCompatibleEmbeddingProvider built = null;
        RagProperties.Embedding config = properties.getEmbedding();
        if ("openai-compatible".equalsIgnoreCase(config.getProvider())) {
            try {
                built = new OpenAiCompatibleEmbeddingProvider(
                        config.getBaseUrl(),
                        config.getModel(),
                        config.getApiKey(),
                        config.getDim(),
                        config.getTimeoutMs(),
                        "gemini".equalsIgnoreCase(config.getMode()));
            } catch (RuntimeException exception) {
                built = null;
            }
        }
        this.external = built;
    }

    public LocalLexicalEmbedding local() {
        return local;
    }

    public OpenAiCompatibleEmbeddingProvider external() {
        return external;
    }

    public boolean externalUsable() {
        return external != null;
    }

    /** The provider whose vectors are persisted. */
    public EmbeddingProvider primary() {
        return external != null ? external : local;
    }
}
