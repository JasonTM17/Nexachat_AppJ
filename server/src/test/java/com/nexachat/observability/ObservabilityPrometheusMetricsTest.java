package com.nexachat.observability;

import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Prometheus registry, unlike the simple registry, rejects a second meter
 * of the same name whose tag keys differ from the first. These tests run the
 * real registry so a tag-set mismatch fails here instead of silently dropping
 * series in production.
 */
class ObservabilityPrometheusMetricsTest {

    @Test
    void repositoryCallsReachTheScrapeWithMethodAndOutcome() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

        metrics.recordRepositoryCall("MessageRepository.findRowsAfter", 12, true);
        metrics.recordRepositoryCall("UserRepository.findByPhoneAndDeletedAtIsNull", 3, false);

        String scrape = registry.scrape();
        assertThat(seriesLines(scrape, "nexachat_repository_calls_seconds_count"))
                .anySatisfy(line -> {
                    assertThat(line).contains("method=\"MessageRepository.findRowsAfter\"");
                    assertThat(line).contains("outcome=\"SUCCESS\"");
                });
        assertThat(seriesLines(scrape, "nexachat_repository_calls_seconds_count"))
                .anySatisfy(line -> {
                    assertThat(line).contains("method=\"UserRepository.findByPhoneAndDeletedAtIsNull\"");
                    assertThat(line).contains("outcome=\"FAILURE\"");
                });
    }

    @Test
    void everyTaggedMeterIsRegisteredOnAPrometheusRegistry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

        metrics.recordMessageSent("TEXT", true);
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

        assertThat(registry.find(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "TEXT").tag("outcome", "CREATED").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "CONNECT").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_DURATION)
                .tag("outcome", "SUCCESS").timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_BYTES)
                .tag("size_bucket", "1KB-10KB").summary()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "SUCCESS").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.CALL_TRANSITIONS)
                .tag("action", "accept").tag("to", "ACTIVE").tag("reason", "NONE")
                .counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.RAG_REQUESTS)
                .tag("mode", "LLM").tag("provider", "lexical").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.SUMMARY_REQUESTS)
                .tag("provider", "LLM").tag("mode", "GENERATE").tag("fallback", "LLM_TIMEOUT")
                .counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.LLM_DURATION)
                .tag("source", "rag").tag("provider", "llm").tag("outcome", "FAILURE")
                .tag("fallback", "NONE").timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "message-send").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.AUTH_FAILURES)
                .tag("reason", "TOKEN_EXPIRED").counter()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.REPOSITORY_CALLS)
                .tag("method", "MessageRepository.findRowsAfter").tag("outcome", "SUCCESS")
                .timer()).isNotNull();
    }

    @Test
    void noMeterRegistrationFailsAfterEveryRecordingMethodRuns() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
                .throwExceptionOnRegistrationFailure();
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

        recordEveryMetric(metrics);

        FirebaseProjectionMetrics firebase = new FirebaseProjectionMetrics(registry);
        firebase.write("CALL", () -> {
        });
        firebase.retry("SUCCESS");
        firebase.backfillRows("MESSAGE", 2);
        firebase.expiredRows("MESSAGE", 1);
    }

    @Test
    void allSeriesOfOneNexachatMeterShareOneTagKeySet() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);
        recordEveryMetric(metrics);

        Map<String, Set<Set<String>>> keySetsByName = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("nexachat."))
                .collect(Collectors.groupingBy(
                        meter -> meter.getId().getName(),
                        Collectors.mapping(
                                meter -> meter.getId().getTags().stream()
                                        .map(Tag::getKey)
                                        .collect(Collectors.toSet()),
                                Collectors.toSet())));

        assertThat(keySetsByName).isNotEmpty();
        keySetsByName.forEach((name, keySets) ->
                assertThat(keySets).as("tag key sets for %s", name).hasSize(1));
    }

    private static List<String> seriesLines(String scrape, String metricName) {
        return scrape.lines()
                .filter(line -> line.startsWith(metricName))
                .collect(Collectors.toList());
    }

    private static void recordEveryMetric(ObservabilityMetrics metrics) {
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
