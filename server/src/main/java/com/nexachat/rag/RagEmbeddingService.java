package com.nexachat.rag;

import com.nexachat.summary.SummaryConsentRepository;
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
import java.util.function.Supplier;

/**
 * Computes and stores embeddings. Retrieval always degrades to the offline
 * lexical provider when the caller has not consented to external AI processing,
 * when requester consent is revoked before an external call, or when an external
 * call fails, so the ask endpoint never leaks data or becomes unavailable
 * because an embedding vendor is down.
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
    private final SummaryConsentRepository consentRepository;

    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor) {
        this(providers, index, cache, properties, stats, redactor,
                new CircuitBreaker(Clock.systemUTC()), null);
    }

    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor,
            @Qualifier("ragEmbeddingCircuitBreaker") CircuitBreaker circuitBreaker) {
        this(providers, index, cache, properties, stats, redactor, circuitBreaker, null);
    }

    @Autowired
    public RagEmbeddingService(
            RagEmbeddingProviders providers,
            RagMessageIndex index,
            VectorCache cache,
            RagProperties properties,
            RagStats stats,
            Redactor redactor,
            @Qualifier("ragEmbeddingCircuitBreaker") CircuitBreaker circuitBreaker,
            @Autowired(required = false) SummaryConsentRepository consentRepository) {
        this.providers = providers;
        this.index = index;
        this.cache = cache;
        this.properties = properties;
        this.stats = stats;
        this.redactor = redactor;
        this.circuitBreaker = circuitBreaker;
        this.consentRepository = consentRepository;
    }

    public EmbeddingOutcome embedQuestion(String question, boolean consented) {
        return embedQuestion(null, question, consented);
    }

    public EmbeddingOutcome embedQuestion(UUID userId, String question, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            float[] vector = embed(userId, provider, inputFor(question, provider));
            return new EmbeddingOutcome(vector, provider.id());
        } catch (EmbeddingProviderException exception) {
            float[] vector = embed(null, providers.local(), question == null ? "" : question);
            return new EmbeddingOutcome(vector, providers.local().id());
        }
    }

    public VectorOutcome ensureVectors(List<RagMessage> messages, int limit, boolean consented) {
        return ensureVectors(null, messages, limit, consented);
    }

    public VectorOutcome ensureVectors(UUID userId, List<RagMessage> messages, int limit, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            return ensureVectorsWith(userId, provider, messages, limit);
        } catch (EmbeddingProviderException exception) {
            return ensureVectorsWith(null, providers.local(), messages, limit);
        }
    }

    /**
     * Embeds the question and the candidate messages with one provider so the
     * question vector and the message vectors always share the same model.
     */
    public RetrievalEmbeddings embedForRetrieval(
            String question, List<RagMessage> candidates, int limit, boolean consented) {
        return embedForRetrieval(null, question, candidates, limit, consented);
    }

    public RetrievalEmbeddings embedForRetrieval(
            UUID userId, String question, List<RagMessage> candidates, int limit, boolean consented) {
        EmbeddingProvider provider = runtimeProvider(consented);
        try {
            float[] questionVector = embed(userId, provider, inputFor(question, provider));
            int vectorBudget = provider == providers.external()
                    ? Math.min(limit, Math.max(0, properties.getIndex().getExternalLazyLimit()))
                    : limit;
            VectorOutcome vectors = ensureVectorsWith(userId, provider, candidates, vectorBudget);
            return new RetrievalEmbeddings(questionVector, vectors.vectors(), provider.id());
        } catch (EmbeddingProviderException exception) {
            float[] questionVector = embed(null, providers.local(), question == null ? "" : question);
            VectorOutcome vectors = ensureVectorsWith(null, providers.local(), candidates, limit);
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
                ensureVectorsWith(null, provider, batch, batch.size(), true);
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

    private VectorOutcome ensureVectorsWith(
            UUID userId, EmbeddingProvider provider, List<RagMessage> messages, int limit) {
        return ensureVectorsWith(userId, provider, messages, limit, provider == providers.primary());
    }

    private VectorOutcome ensureVectorsWith(
            UUID userId, EmbeddingProvider provider, List<RagMessage> messages, int limit, boolean persist) {
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
        List<RagMessage> batch = missing.size() > budget
                ? List.copyOf(missing.subList(missing.size() - budget, missing.size()))
                : missing;
        if (!batch.isEmpty()) {
            List<String> inputs = batch.stream()
                    .map(message -> inputFor(message.content(), provider))
                    .toList();
            List<float[]> vectors = embedAll(userId, provider, inputs);
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

    private void requireConsentIfExternal(EmbeddingProvider provider, UUID userId) {
        // Re-read consent right before an external HTTP call so a mid-request revocation
        // aborts before text leaves the boundary and falls back to lexical without
        // penalizing the external provider circuit breaker.
        if (provider != providers.local()
                && userId != null
                && consentRepository != null
                && !consentRepository.isConsented(userId)) {
            throw new EmbeddingConsentRevokedException();
        }
    }

    private float[] embed(UUID userId, EmbeddingProvider provider, String input) {
        return executeEmbeddingCall(userId, provider, 1, "embedding request failed",
                () -> provider.embed(input));
    }

    private List<float[]> embedAll(UUID userId, EmbeddingProvider provider, List<String> inputs) {
        return executeEmbeddingCall(userId, provider, inputs.size(), "embedding batch failed",
                () -> provider.embedAll(inputs));
    }

    private <T> T executeEmbeddingCall(
            UUID userId,
            EmbeddingProvider provider,
            int inputCount,
            String failureMessage,
            Supplier<T> call) {
        requireConsentIfExternal(provider, userId);
        if (provider != providers.local() && !circuitBreaker.allowRequest()) {
            throw new EmbeddingProviderException("embedding circuit breaker is open");
        }
        try {
            T result = call.get();
            if (provider != providers.local()) {
                stats.recordEmbeddingCall(inputCount);
                circuitBreaker.recordSuccess();
            }
            return result;
        } catch (RuntimeException exception) {
            if (provider != providers.local()) {
                stats.recordEmbeddingFailure();
                circuitBreaker.recordFailure();
            }
            if (exception instanceof EmbeddingProviderException providerException) {
                throw providerException;
            }
            throw new EmbeddingProviderException(failureMessage, exception);
        }
    }

    private static final class EmbeddingConsentRevokedException extends EmbeddingProviderException {
        private EmbeddingConsentRevokedException() {
            super("AI consent revoked");
        }
    }

    public record EmbeddingOutcome(float[] vector, String model) {
    }

    public record VectorOutcome(Map<UUID, float[]> vectors, String model) {
    }

    public record RetrievalEmbeddings(float[] questionVector, Map<UUID, float[]> vectors, String model) {
    }
}
