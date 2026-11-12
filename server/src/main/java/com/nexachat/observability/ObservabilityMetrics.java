package com.nexachat.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Low-cardinality Micrometer meters for the server.
 *
 * <p>Tag discipline is the contract: no user ids, phone numbers, email
 * addresses, message text, tokens or connection strings may become a tag.
 * Every tag key used by this class is listed in {@link #ALLOWED_TAG_KEYS};
 * the observability test asserts the registry holds nothing else.
 *
 * <p>Base meters are registered eagerly so {@code /actuator/metrics} shows
 * the full surface even before the first request.
 */
@Component
public class ObservabilityMetrics {

    public static final String MESSAGES_SENT = "nexachat.messages.sent";
    public static final String OUTBOX_REPLAYS = "nexachat.outbox.replays";
    public static final String IDEMPOTENCY_REPLAYS = "nexachat.idempotency.replays";
    public static final String STOMP_EVENTS = "nexachat.stomp.events";
    public static final String DELTA_SYNC_DURATION = "nexachat.delta_sync.duration";
    public static final String DELTA_SYNC_BYTES = "nexachat.delta_sync.response_bytes";
    public static final String PUSH_SENDS = "nexachat.push.sends";
    public static final String CALL_TRANSITIONS = "nexachat.call.transitions";
    public static final String RAG_REQUESTS = "nexachat.rag.requests";
    public static final String SUMMARY_REQUESTS = "nexachat.summary.requests";
    public static final String LLM_DURATION = "nexachat.llm.duration";
    public static final String RATE_LIMIT_REJECTIONS = "nexachat.ratelimit.rejections";
    public static final String AUTH_FAILURES = "nexachat.auth.failures";
    public static final String REPOSITORY_CALLS = "nexachat.repository.calls";
    public static final String STORAGE_OPERATIONS = "nexachat.storage.operations";
    public static final String STORAGE_OPERATION_DURATION = "nexachat.storage.operation.duration";
    public static final String STORAGE_OPERATION_BYTES = "nexachat.storage.operation.bytes";
    public static final String STORAGE_STREAM_DURATION = "nexachat.storage.stream.duration";
    public static final String STORAGE_STREAM_BYTES = "nexachat.storage.stream.bytes";

    /** Every tag key this class is allowed to emit. */
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "type", "outcome", "event", "size_bucket", "result", "action",
            "to", "reason", "mode", "provider", "fallback", "source", "method",
            "operation");

    /** Terminal state of consuming a storage stream. */
    public enum StreamOutcome {
        /** The caller read to end of stream. */
        COMPLETE,
        /** The caller closed the stream before end of stream. */
        CLOSED,
        /** A read, skip or close raised an I/O error. */
        FAILURE
    }

    /**
     * Tag value of the zero-valued warm-up series of a tagged meter.
     *
     * <p>Prometheus requires every series of one meter name to declare the
     * same tag keys. Registering a tagged meter eagerly without tags makes the
     * first real observation fail registration, so the warm-up series carries
     * the full tag key set with this value. It is never incremented or
     * recorded and does not appear among the real tag values.
     */
    static final String UNRECORDED = "none";

    private final MeterRegistry registry;

    public ObservabilityMetrics(MeterRegistry registry) {
        this.registry = registry;
        Counter.builder(MESSAGES_SENT)
                .description("User and system messages accepted by the send path")
                .tags("type", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        Counter.builder(OUTBOX_REPLAYS)
                .description("Message sends deduplicated by clientMessageId (outbox replay)")
                .register(registry);
        Counter.builder(IDEMPOTENCY_REPLAYS)
                .description("Idempotent mutation replays served from the idempotency cache")
                .register(registry);
        Counter.builder(STOMP_EVENTS)
                .description("STOMP connection, subscription and disconnection events")
                .tags("event", UNRECORDED)
                .register(registry);
        Timer.builder(DELTA_SYNC_DURATION)
                .description("Delta-sync request duration")
                .publishPercentileHistogram()
                .tags("outcome", UNRECORDED)
                .register(registry);
        DistributionSummary.builder(DELTA_SYNC_BYTES)
                .description("Delta-sync response size")
                .tags("size_bucket", UNRECORDED)
                .register(registry);
        Counter.builder(PUSH_SENDS)
                .description("Push notification dispatch results")
                .tags("result", UNRECORDED)
                .register(registry);
        Counter.builder(CALL_TRANSITIONS)
                .description("Call state machine transitions")
                .tags("action", UNRECORDED, "to", UNRECORDED, "reason", UNRECORDED)
                .register(registry);
        Counter.builder(RAG_REQUESTS)
                .description("RAG ask requests by answer mode and embedding provider")
                .tags("mode", UNRECORDED, "provider", UNRECORDED)
                .register(registry);
        Counter.builder(SUMMARY_REQUESTS)
                .description("Summary requests by provider, mode and fallback reason")
                .tags("provider", UNRECORDED, "mode", UNRECORDED, "fallback", UNRECORDED)
                .register(registry);
        Timer.builder(LLM_DURATION)
                .description("LLM completion latency by source and provider")
                .tags("source", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED,
                        "fallback", UNRECORDED)
                .register(registry);
        Counter.builder(RATE_LIMIT_REJECTIONS)
                .description("Rate-limit rejections by rule")
                .tags("reason", UNRECORDED)
                .register(registry);
        Counter.builder(AUTH_FAILURES)
                .description("Security-layer authentication and authorization failures by reason")
                .tags("reason", UNRECORDED)
                .register(registry);
        Timer.builder(REPOSITORY_CALLS)
                .description("Repository call duration by method")
                .tags("method", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        Counter.builder(STORAGE_OPERATIONS)
                .description("Storage operations by operation, provider and outcome")
                .tags("operation", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        Timer.builder(STORAGE_OPERATION_DURATION)
                .description("Storage operation duration up to the point the operation returns")
                .publishPercentileHistogram()
                .tags("operation", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        DistributionSummary.builder(STORAGE_OPERATION_BYTES)
                .description("Bytes moved by the storage operation itself")
                .tags("operation", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        Timer.builder(STORAGE_STREAM_DURATION)
                .description("Time spent consuming a storage stream after it was opened")
                .publishPercentileHistogram()
                .tags("operation", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
        DistributionSummary.builder(STORAGE_STREAM_BYTES)
                .description("Bytes consumed from a storage stream")
                .tags("operation", UNRECORDED, "provider", UNRECORDED, "outcome", UNRECORDED)
                .register(registry);
    }

    public void recordMessageSent(String type, boolean created) {
        registry.counter(MESSAGES_SENT, "type", safe(type, "UNKNOWN"),
                "outcome", created ? "CREATED" : "REPLAY").increment();
    }

    public void recordSystemMessage() {
        registry.counter(MESSAGES_SENT, "type", "SYSTEM", "outcome", "CREATED").increment();
    }

    public void recordOutboxReplay() {
        registry.counter(OUTBOX_REPLAYS).increment();
    }

    public void recordIdempotencyReplay() {
        registry.counter(IDEMPOTENCY_REPLAYS).increment();
    }

    public void recordStompEvent(String event) {
        registry.counter(STOMP_EVENTS, "event", safe(event, "UNKNOWN")).increment();
    }

    public void recordDeltaSync(long durationMillis, long responseBytes, boolean success) {
        histogramTimer(DELTA_SYNC_DURATION, "outcome", success ? "SUCCESS" : "ERROR")
                .record(durationMillis, TimeUnit.MILLISECONDS);
        registry.summary(DELTA_SYNC_BYTES, "size_bucket", sizeBucket(responseBytes))
                .record(Math.max(0L, responseBytes));
    }

    public void recordPushSend(String result) {
        registry.counter(PUSH_SENDS, "result", safe(result, "UNKNOWN")).increment();
    }

    public void recordCallTransition(String action, String to, String reason) {
        registry.counter(CALL_TRANSITIONS, "action", safe(action, "UNKNOWN"),
                "to", safe(to, "UNKNOWN"), "reason", safe(reason, "NONE")).increment();
    }

    public void recordRagRequest(String mode, String provider) {
        registry.counter(RAG_REQUESTS, "mode", safe(mode, "UNKNOWN"),
                "provider", safe(provider, "UNKNOWN")).increment();
    }

    public void recordSummaryRequest(String provider, String mode, String fallback) {
        registry.counter(SUMMARY_REQUESTS, "provider", safe(provider, "UNKNOWN"),
                "mode", safe(mode, "UNKNOWN"), "fallback", safe(fallback, "NONE")).increment();
    }

    public void recordLlmCall(String source, String provider, String outcome,
                              String fallback, long durationMillis) {
        registry.timer(LLM_DURATION, "source", safe(source, "UNKNOWN"),
                "provider", safe(provider, "UNKNOWN"), "outcome", safe(outcome, "UNKNOWN"),
                "fallback", safe(fallback, "NONE")).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public void recordRateLimitRejection(String reason) {
        registry.counter(RATE_LIMIT_REJECTIONS, "reason", safe(reason, "UNKNOWN")).increment();
    }

    public void recordAuthFailure(String reason) {
        registry.counter(AUTH_FAILURES, "reason", safe(reason, "UNKNOWN")).increment();
    }

    public void recordRepositoryCall(String method, long durationMillis, boolean success) {
        registry.timer(REPOSITORY_CALLS, "method", safe(method, "UNKNOWN"),
                "outcome", success ? "SUCCESS" : "FAILURE")
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Records one storage operation at request level. For {@code OPEN} and
     * {@code OPEN_RANGE} this ends when the stream handle is returned; byte
     * consumption is recorded separately by
     * {@link #recordStorageStream(String, String, long, long, StreamOutcome)}.
     */
    public void recordStorageOperation(String operation, String provider, long durationMillis,
                                       long bytes, boolean success) {
        String outcome = success ? "SUCCESS" : "FAILURE";
        String operationTag = safe(operation, "UNKNOWN");
        String providerTag = safe(provider, "UNKNOWN");
        registry.counter(STORAGE_OPERATIONS, "operation", operationTag,
                "provider", providerTag, "outcome", outcome).increment();
        histogramTimer(STORAGE_OPERATION_DURATION, "operation", operationTag,
                "provider", providerTag, "outcome", outcome)
                .record(durationMillis, TimeUnit.MILLISECONDS);
        registry.summary(STORAGE_OPERATION_BYTES, "operation", operationTag,
                "provider", providerTag, "outcome", outcome)
                .record(Math.max(0L, bytes));
    }

    /** Records stream consumption, kept separate from the open operation. */
    public void recordStorageStream(String operation, String provider, long durationMillis,
                                    long bytes, StreamOutcome outcome) {
        String operationTag = safe(operation, "UNKNOWN");
        String providerTag = safe(provider, "UNKNOWN");
        String outcomeTag = outcome == null ? StreamOutcome.FAILURE.name() : outcome.name();
        histogramTimer(STORAGE_STREAM_DURATION, "operation", operationTag,
                "provider", providerTag, "outcome", outcomeTag)
                .record(durationMillis, TimeUnit.MILLISECONDS);
        registry.summary(STORAGE_STREAM_BYTES, "operation", operationTag,
                "provider", providerTag, "outcome", outcomeTag)
                .record(Math.max(0L, bytes));
    }

    /**
     * Returns a timer with the percentile histogram config used by every
     * tagged series of a timer name. Registering a tagged series through
     * {@code registry.timer(name, tags)} without this config would mix summary
     * and histogram data points under one Prometheus name and make the scrape
     * fail, because the first registered meter fixes the exposition family.
     */
    private Timer histogramTimer(String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Bounded size buckets for the delta-sync response summary. */
    static String sizeBucket(long bytes) {
        if (bytes < 1024) {
            return "<1KB";
        }
        if (bytes < 10 * 1024) {
            return "1KB-10KB";
        }
        if (bytes < 100 * 1024) {
            return "10KB-100KB";
        }
        if (bytes < 1024 * 1024) {
            return "100KB-1MB";
        }
        return ">1MB";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
