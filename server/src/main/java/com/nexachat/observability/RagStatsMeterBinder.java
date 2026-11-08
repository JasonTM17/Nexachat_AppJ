package com.nexachat.observability;

import com.nexachat.rag.RagStats;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes the existing aggregate RAG counters as Micrometer counters, so the
 * RAG layer keeps its own bookkeeping and Prometheus still gets monotonic
 * series for rate() queries. Values are counts only; no questions, messages
 * or vectors are read.
 */
@Component
public class RagStatsMeterBinder {

    private static final String PREFIX = "nexachat.rag.stats.";

    public RagStatsMeterBinder(RagStats stats, MeterRegistry registry) {
        FunctionCounter.builder(PREFIX + "requests", stats, value -> value.snapshot().requests())
                .description("RAG ask requests")
                .register(registry);
        FunctionCounter.builder(PREFIX + "cache_hits", stats, value -> value.snapshot().cacheHits())
                .description("RAG cache hits")
                .register(registry);
        FunctionCounter.builder(PREFIX + "not_found", stats, value -> value.snapshot().notFound())
                .description("RAG answers classified as not found")
                .register(registry);
        FunctionCounter.builder(PREFIX + "extractive", stats, value -> value.snapshot().extractive())
                .description("RAG extractive answers")
                .register(registry);
        FunctionCounter.builder(PREFIX + "llm_calls", stats, value -> value.snapshot().llmCalls())
                .description("RAG LLM completions")
                .register(registry);
        FunctionCounter.builder(PREFIX + "llm_failures", stats, value -> value.snapshot().llmFailures())
                .description("RAG LLM failures")
                .register(registry);
        FunctionCounter.builder(PREFIX + "embedding_calls", stats, value -> value.snapshot().embeddingCalls())
                .description("Embedding calls")
                .register(registry);
        FunctionCounter.builder(PREFIX + "embedding_failures", stats, value -> value.snapshot().embeddingFailures())
                .description("Embedding failures")
                .register(registry);
        FunctionCounter.builder(PREFIX + "indexed_messages", stats, value -> value.snapshot().indexedMessages())
                .description("Messages indexed for RAG")
                .register(registry);
    }
}
