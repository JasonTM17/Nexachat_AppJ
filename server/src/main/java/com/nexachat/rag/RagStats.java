package com.nexachat.rag;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregate counters for the RAG layer. Counts only: no message text, no
 * questions, no vectors and no user identifiers are retained.
 */
@Component
public class RagStats {

    private final LongAdder requests = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder notFound = new LongAdder();
    private final LongAdder extractive = new LongAdder();
    private final LongAdder llmCalls = new LongAdder();
    private final LongAdder llmFailures = new LongAdder();
    private final LongAdder inputChars = new LongAdder();
    private final LongAdder embeddingCalls = new LongAdder();
    private final LongAdder embeddingFailures = new LongAdder();
    private final LongAdder indexedMessages = new LongAdder();

    public void recordRequest() {
        requests.increment();
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordNotFound() {
        notFound.increment();
    }

    public void recordExtractive() {
        extractive.increment();
    }

    public void recordLlmCall(int chars) {
        llmCalls.increment();
        inputChars.add(Math.max(0, chars));
    }

    public void recordLlmFailure() {
        llmFailures.increment();
    }

    public void recordEmbeddingCall(int count) {
        embeddingCalls.add(Math.max(0, count));
    }

    public void recordEmbeddingFailure() {
        embeddingFailures.increment();
    }

    public void recordIndexedMessages(int count) {
        indexedMessages.add(Math.max(0, count));
    }

    public Snapshot snapshot() {
        long calls = llmCalls.sum();
        double meanInputChars = calls == 0 ? 0.0 : (double) inputChars.sum() / calls;
        return new Snapshot(
                requests.sum(),
                cacheHits.sum(),
                notFound.sum(),
                extractive.sum(),
                calls,
                llmFailures.sum(),
                inputChars.sum(),
                meanInputChars,
                embeddingCalls.sum(),
                embeddingFailures.sum(),
                indexedMessages.sum());
    }

    public void reset() {
        requests.reset();
        cacheHits.reset();
        notFound.reset();
        extractive.reset();
        llmCalls.reset();
        llmFailures.reset();
        inputChars.reset();
        embeddingCalls.reset();
        embeddingFailures.reset();
        indexedMessages.reset();
    }

    public record Snapshot(
            long requests,
            long cacheHits,
            long notFound,
            long extractive,
            long llmCalls,
            long llmFailures,
            long inputChars,
            double meanInputChars,
            long embeddingCalls,
            long embeddingFailures,
            long indexedMessages) {
    }
}
