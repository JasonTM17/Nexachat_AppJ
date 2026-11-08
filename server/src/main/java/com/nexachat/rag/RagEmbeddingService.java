package com.nexachat.rag;

import com.nexachat.summary.llm.Redactor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes and stores embeddings. Retrieval always degrades to the offline
 * lexical provider when an external call fails, so the ask endpoint never
 * becomes unavailable because an embedding vendor is down.
 */
@Service
@Profile("!test")
public class RagEmbeddingService {

    private final RagEmbeddingProviders providers;
    private final RagMessageIndex index;
    private final VectorCache cache;
    private final RagProperties properties;
    private final RagStats stats;
    private final Redactor redactor;

    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor) {
        this.providers = providers;
        this.index = index;
        this.cache = cache;
        this.properties = properties;
        this.stats = stats;
        this.redactor = redactor;
    }

    public EmbeddingOutcome embedQuestion(String question, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            float[] vector = embed(provider, inputFor(question, provider));
            return new EmbeddingOutcome(vector, provider.id());
        } catch (EmbeddingProviderException exception) {
            float[] vector = embed(providers.local(), question == null ? "" : question);
            return new EmbeddingOutcome(vector, providers.local().id());
        }
    }

    public VectorOutcome ensureVectors(List<RagMessage> messages, int limit, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            return ensureVectorsWith(provider, messages, limit);
        } catch (EmbeddingProviderException exception) {
            return ensureVectorsWith(providers.local(), messages, limit);
        }
    }

    /**
     * Embeds the question and the candidate messages with one provider so the
     * question vector and the message vectors always share the same model.
     */
    public RetrievalEmbeddings embedForRetrieval(
            String question, List<RagMessage> candidates, int limit, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            float[] questionVector = embed(provider, inputFor(question, provider));
            VectorOutcome vectors = ensureVectorsWith(provider, candidates, limit);
            return new RetrievalEmbeddings(questionVector, vectors.vectors(), provider.id());
        } catch (EmbeddingProviderException exception) {
            float[] questionVector = embed(providers.local(), question == null ? "" : question);
            VectorOutcome vectors = ensureVectorsWith(providers.local(), candidates, limit);
            return new RetrievalEmbeddings(questionVector, vectors.vectors(), providers.local().id());
        }
    }

    /** Background backfill; returns the number of messages indexed this run. */
    public int backfill() {
        if (!properties.isEnabled() || !properties.getIndex().isBackfillEnabled()) {
            return 0;
        }
        EmbeddingProvider provider = providers.primary();
        String model = provider.id();
        int budget = Math.max(1, properties.getIndex().getBackfillRatePerMinute());
        int batchSize = Math.max(1, Math.min(properties.getEmbedding().getBatchSize(), budget));
        int indexed = 0;
        while (indexed < budget) {
            List<RagMessage> batch = index.findUnindexed(model, Math.min(batchSize, budget - indexed));
            if (batch.isEmpty()) {
                break;
            }
            try {
                ensureVectorsWith(provider, batch, batch.size());
                indexed += batch.size();
            } catch (EmbeddingProviderException exception) {
                break;
            }
        }
        return indexed;
    }

    private VectorOutcome ensureVectorsWith(EmbeddingProvider provider, List<RagMessage> messages, int limit) {
        if (messages == null || messages.isEmpty()) {
            return new VectorOutcome(Map.of(), provider.id());
        }
        String model = provider.id();
        Map<UUID, RagMessage> unique = new LinkedHashMap<>();
        for (RagMessage message : messages) {
            if (message != null && message.id() != null) {
                unique.putIfAbsent(message.id(), message);
            }
        }
        List<UUID> ids = List.copyOf(unique.keySet());
        Map<UUID, String> storedHashes = index.loadContentHashes(model, ids);
        Map<UUID, float[]> storedVectors = index.loadVectors(model, ids);
        Map<UUID, float[]> result = new HashMap<>();
        List<RagMessage> missing = new ArrayList<>();
        for (RagMessage message : unique.values()) {
            String inputHash = RagHashes.embeddingInputHash(inputFor(message.content(), provider));
            if (inputHash.equals(storedHashes.get(message.id()))) {
                float[] cached = cache.get(message.id());
                if (cached != null) {
                    result.put(message.id(), cached);
                    continue;
                }
                float[] loaded = storedVectors.get(message.id());
                if (loaded != null) {
                    cache.put(message.id(), loaded);
                    result.put(message.id(), loaded);
                    continue;
                }
            }
            missing.add(message);
        }

        int budget = Math.max(0, limit);
        List<RagMessage> batch = missing.size() > budget ? List.copyOf(missing.subList(0, budget)) : missing;
        if (!batch.isEmpty()) {
            List<String> inputs = batch.stream()
                    .map(message -> inputFor(message.content(), provider))
                    .toList();
            List<float[]> vectors = embedAll(provider, inputs);
            Map<UUID, float[]> savedVectors = new HashMap<>();
            Map<UUID, String> savedHashes = new HashMap<>();
            for (int position = 0; position < batch.size(); position++) {
                RagMessage message = batch.get(position);
                float[] vector = vectors.get(position);
                String hash = RagHashes.embeddingInputHash(inputs.get(position));
                savedVectors.put(message.id(), vector);
                savedHashes.put(message.id(), hash);
                cache.put(message.id(), vector);
                result.put(message.id(), vector);
            }
            if (provider == providers.primary()) {
                index.saveVectors(model, provider.dimension(), batch, savedVectors, savedHashes);
                stats.recordIndexedMessages(batch.size());
            }
        }
        return new VectorOutcome(result, model);
    }

    private EmbeddingProvider runtimeProvider(boolean consented) {
        return consented && providers.externalUsable() ? providers.external() : providers.local();
    }

    private String inputFor(String text, EmbeddingProvider provider) {
        if (provider == providers.local()) {
            return text == null ? "" : text;
        }
        return redactor.redact(text);
    }

    private float[] embed(EmbeddingProvider provider, String input) {
        try {
            float[] vector = provider.embed(input);
            if (provider != providers.local()) {
                stats.recordEmbeddingCall(1);
            }
            return vector;
        } catch (RuntimeException exception) {
            if (provider != providers.local()) {
                stats.recordEmbeddingFailure();
            }
            if (exception instanceof EmbeddingProviderException providerException) {
                throw providerException;
            }
            throw new EmbeddingProviderException("embedding request failed", exception);
        }
    }

    private List<float[]> embedAll(EmbeddingProvider provider, List<String> inputs) {
        try {
            List<float[]> vectors = provider.embedAll(inputs);
            if (provider != providers.local()) {
                stats.recordEmbeddingCall(inputs.size());
            }
            return vectors;
        } catch (RuntimeException exception) {
            if (provider != providers.local()) {
                stats.recordEmbeddingFailure();
            }
            if (exception instanceof EmbeddingProviderException providerException) {
                throw providerException;
            }
            throw new EmbeddingProviderException("embedding batch failed", exception);
        }
    }

    public record EmbeddingOutcome(float[] vector, String model) {
    }

    public record VectorOutcome(Map<UUID, float[]> vectors, String model) {
    }

    public record RetrievalEmbeddings(float[] questionVector, Map<UUID, float[]> vectors, String model) {
    }
}
