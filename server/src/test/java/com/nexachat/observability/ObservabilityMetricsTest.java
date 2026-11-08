package com.nexachat.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityMetricsTest {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{9,}$");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

    @Test
    void registersEveryMetricNameEagerly() {
        assertThat(registry.find(ObservabilityMetrics.MESSAGES_SENT).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.OUTBOX_REPLAYS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.IDEMPOTENCY_REPLAYS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.STOMP_EVENTS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_DURATION).timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_BYTES).summary()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.PUSH_SENDS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.CALL_TRANSITIONS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.RAG_REQUESTS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.SUMMARY_REQUESTS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.LLM_DURATION).timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.RATE_LIMIT_REJECTIONS).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.AUTH_FAILURES).counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.REPOSITORY_CALLS).timer()).isNotNull();
    }

    @Test
    void recordsMessageSendReplayAndSystemMessage() {
        metrics.recordMessageSent("TEXT", true);
        metrics.recordMessageSent("IMAGE", false);
        metrics.recordSystemMessage();

        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "TEXT").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "IMAGE").tag("outcome", "REPLAY").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "SYSTEM").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.OUTBOX_REPLAYS).counter().count()).isEqualTo(0);
    }

    @Test
    void recordsStompPushAndAuthMetrics() {
        metrics.recordStompEvent("CONNECT");
        metrics.recordPushSend("SUCCESS");
        metrics.recordPushSend("UNREGISTERED");
        metrics.recordRateLimitRejection("message-send");
        metrics.recordAuthFailure("TOKEN_EXPIRED");

        assertThat(registry.get(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "CONNECT").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "UNREGISTERED").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "message-send").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.AUTH_FAILURES)
                .tag("reason", "TOKEN_EXPIRED").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsAiAndRepositoryMetrics() {
        metrics.recordRagRequest("LLM", "lexical");
        metrics.recordSummaryRequest("LLM", "GENERATE", "LLM_TIMEOUT");
        metrics.recordLlmCall("rag", "llm", "FAILURE", "NONE", 42);
        metrics.recordRepositoryCall("MessageRepository.findRowsAfter", 12, true);

        assertThat(registry.get(ObservabilityMetrics.RAG_REQUESTS)
                .tag("mode", "LLM").tag("provider", "lexical").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.SUMMARY_REQUESTS)
                .tag("provider", "LLM").tag("mode", "GENERATE").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.LLM_DURATION)
                .tag("source", "rag").tag("outcome", "FAILURE").timer().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.REPOSITORY_CALLS)
                .tag("method", "MessageRepository.findRowsAfter").timer().count()).isEqualTo(1);
    }

    @Test
    void everyNexachatTagKeyIsAllowedAndFreeOfPersonalData() {
        recordEveryMetric();

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("nexachat.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ObservabilityMetrics.ALLOWED_TAG_KEYS)
                        .as("tag key %s on %s", tag.getKey(), meter.getId().getName())
                        .contains(tag.getKey());
                assertThat(tag.getValue())
                        .as("tag value %s on %s", tag.getValue(), meter.getId().getName())
                        .doesNotContain("@")
                        .doesNotMatch(UUID)
                        .doesNotMatch(EMAIL)
                        .doesNotMatch(PHONE);
            }
        }
    }

    private void recordEveryMetric() {
        metrics.recordMessageSent("TEXT", true);
        metrics.recordMessageSent("IMAGE", false);
        metrics.recordSystemMessage();
        metrics.recordOutboxReplay();
        metrics.recordIdempotencyReplay();
        metrics.recordStompEvent("CONNECT");
        metrics.recordDeltaSync(10, 2048, true);
        metrics.recordPushSend("SUCCESS");
        metrics.recordCallTransition("accept", "ACTIVE", "NONE");
        metrics.recordRagRequest("LLM", "lexical");
        metrics.recordSummaryRequest("LLM", "GENERATE", "LLM_TIMEOUT");
        metrics.recordLlmCall("rag", "llm", "FAILURE", "NONE", 42);
        metrics.recordRateLimitRejection("message-send");
        metrics.recordAuthFailure("TOKEN_EXPIRED");
        metrics.recordRepositoryCall("MessageRepository.findRowsAfter", 12, true);
    }
}
