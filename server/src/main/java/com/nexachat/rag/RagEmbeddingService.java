package com.nexachat.rag;

import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.Redactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
    private final CircuitBreaker circuitBreaker;

    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor) {
        this(providers, index, cache, properties, stats, redactor, new CircuitBreaker(Clock.systemUTC()));
    }

    @Autowired
    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor,
            @Qualifier("ragEmbeddingCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.providers = providers;
        this.index = index;
        this.cache = cache;
        this.properties = properties;
        this.stats = stats;
        this.redactor = redactor;
        this.circuitBreaker = circuitBreaker;
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
        EmbeddingProvider provider = backfillProvider();
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
                ensureVectorsWith(provider, batch, batch.size(), true);
                indexed += batch.size();
            } catch (EmbeddingProviderException exception) {
                break;
            }
        }
        return indexed;
    }

    private EmbeddingProvider backfillProvider() {
        if (providers.externalUsable()) {
            return providers.local();
        }
        return providers.primary();
    }

    private VectorOutcome ensureVectorsWith(EmbeddingProvider provider, List<RagMessage> messages, int limit) {
        return ensureVectorsWith(provider, messages, limit, provider == providers.primary());
    }

    private VectorOutcome ensureVectorsWith(
            EmbeddingProvider provider, List<RagMessage> messages, int limit, boolean persist) {
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
        if (unique.isEmpty()) {
            return new VectorOutcome(Map.of(), provider.id());
        }
        // Re-check the opt-out state at the moment the embedding batch is built:
        // a backfill row can be selected before a member opts out and committed
        // afterwards, and no opted-out text may reach an external provider.
        java.util.Set<UUID> excluded = index.optedOutMessageIds(unique.keySet());
        if (!excluded.isEmpty()) {
            unique.keySet().removeAll(excluded);
            if (unique.isEmpty()) {
                return new VectorOutcome(Map.of(), provider.id());
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
                float[] cached = cache.get(model, message.id());
                if (cached != null) {
                    result.put(message.id(), cached);
                    continue;
                }
                float[] loaded = storedVectors.get(message.id());
                if (loaded != null) {
                    cache.put(model, message.id(), loaded);
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
                cache.put(model, message.id(), vector);
                result.put(message.id(), vector);
            }
            if (persist) {
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
        if (provider != providers.local() && !circuitBreaker.allowRequest()) {
            throw new EmbeddingProviderException("embedding circuit breaker is open");
        }
        try {
            float[] vector = provider.embed(input);
            if (provider != providers.local()) {
                stats.recordEmbeddingCall(1);
                circuitBreaker.recordSuccess();
            }
            return vector;
        } catch (RuntimeException exception) {
            if (provider != providers.local()) {
                stats.recordEmbeddingFailure();
                circuitBreaker.recordFailure();
            }
            if (exception instanceof EmbeddingProviderException providerException) {
                throw providerException;
            }
            throw new EmbeddingProviderException("embedding request failed", exception);
        }
    }

    private List<float[]> embedAll(EmbeddingProvider provider, List<String> inputs) {
        if (provider != providers.local() && !circuitBreaker.allowRequest()) {
            throw new EmbeddingProviderException("embedding circuit breaker is open");
        }
        try {
            List<float[]> vectors = provider.embedAll(inputs);
            if (provider != providers.local()) {
                stats.recordEmbeddingCall(inputs.size());
                circuitBreaker.recordSuccess();
            }
            return vectors;
        } catch (RuntimeException exception) {
            if (provider != providers.local()) {
                stats.recordEmbeddingFailure();
                circuitBreaker.recordFailure();
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
