package com.nexachat.summary.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryParticipant;
import com.nexachat.summary.SummaryProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientFallbackTest {

    private FakeOpenAiServer primary;
    private FakeOpenAiServer fallback;
    private LlmProperties properties;
    private LlmFallbackProperties fallbackProperties;
    private LlmClient client;

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (primary != null) {
            primary.close();
        }
        if (fallback != null) {
            fallback.close();
        }
    }

    @Test
    void primarySuccessDoesNotCallTheFallback() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Primary answered."))));

        LlmClient.LlmCallResult result =
                client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget());

        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.content()).contains("Primary answered.");
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void primaryServerErrorFallsBackOnce() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback answered."))));

        LlmClient.LlmCallResult result =
                client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.content()).contains("Fallback answered.");
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void primaryRateLimitFallsBackOnce() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 429, "slow down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback answered."))));

        LlmClient.LlmCallResult result =
                client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void primaryTimeoutFallsBackOnce() throws Exception {
        startServers(150);
        primary.handle((exchange, body, authorization) -> {
            // Deliberate fake-server delay: the slow response is what must make
            // the primary call time out and trigger the fallback.
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse(validSummary("Late.")));
        });
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback answered."))));

        LlmClient.LlmCallResult result =
                client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget());

        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.content()).contains("Fallback answered.");
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void primaryAuthFailureDoesNotFallBackAndDoesNotLeakTheBody() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));

        assertThatThrownBy(() -> client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                .isInstanceOf(SummaryProviderException.class)
                .satisfies(error -> {
                    SummaryProviderException exception = (SummaryProviderException) error;
                    assertThat(exception.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain("bad key", "primary-key", "fallback-key");
                });
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void primaryBadRequestDoesNotFallBack() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 400, "malformed"));

        assertThatThrownBy(() -> client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                .isInstanceOf(SummaryProviderException.class)
                .satisfies(error -> assertThat(((SummaryProviderException) error).isRetryable()).isFalse());
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void malformedPrimaryOutputDoesNotFallBack() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) ->
                FakeOpenAiServer.respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":42}}]}"));

        assertThatThrownBy(() -> client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                .isInstanceOf(SummaryProviderException.class)
                .satisfies(error -> {
                    assertThat(((SummaryProviderException) error).fallbackReason())
                            .isEqualTo(SummaryFallbackReason.LLM_BAD_OUTPUT);
                    assertThat(((SummaryProviderException) error).isRetryable()).isFalse();
                });
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void bothProvidersFailingThrowsWithoutLeakingEitherBody() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 503, "fallback down"));

        assertThatThrownBy(() -> client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                .isInstanceOf(SummaryProviderException.class)
                .satisfies(error -> {
                    SummaryProviderException exception = (SummaryProviderException) error;
                    assertThat(exception.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("primary down", "fallback down");
                });
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void disabledFallbackIsNeverCalled() throws Exception {
        startServers();
        fallbackProperties.setEnabled(false);
        client.close();
        client = new LlmClient(properties, fallbackProperties, new ObjectMapper());
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));

        assertThat(client.fallbackTarget()).isNull();
        assertThatThrownBy(() -> client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                .isInstanceOf(SummaryProviderException.class);
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void fallbackReceivesTheSamePromptAndOnlyTheModelDiffers() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback answered."))));

        client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget());

        JsonNode primaryBody = new ObjectMapper().readTree(primary.requests().get(0).body());
        JsonNode fallbackBody = new ObjectMapper().readTree(fallback.requests().get(0).body());
        assertThat(fallbackBody.path("messages")).isEqualTo(primaryBody.path("messages"));
        assertThat(fallbackBody.path("max_tokens").asInt()).isEqualTo(primaryBody.path("max_tokens").asInt());
        assertThat(primaryBody.path("model").asText()).isEqualTo("primary-model");
        assertThat(fallbackBody.path("model").asText()).isEqualTo("fallback-model");
    }

    @Test
    void keysNeverAppearInExceptionsTargetsBodiesOrLogs() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            assertThatThrownBy(() ->
                    client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget()))
                    .isInstanceOf(SummaryProviderException.class);

            assertThat(client.cloudTarget().toString()).doesNotContain("primary-key");
            assertThat(client.fallbackTarget().toString()).doesNotContain("fallback-key");
            assertThat(primary.requests().get(0).body()).doesNotContain("primary-key");
            assertThat(primary.requests().get(0).authorization()).isEqualTo("Bearer primary-key");
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("primary-key") || message.contains("fallback-key"));
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void concurrentFallbackCallsAllSucceed() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback answered."))));

        int calls = 8;
        ExecutorService executor = Executors.newFixedThreadPool(calls);
        try {
            List<Future<LlmClient.LlmCallResult>> results = new ArrayList<>();
            for (int index = 0; index < calls; index++) {
                results.add(executor.submit(() ->
                        client.completeWithFallback(prompt(), client.cloudTarget(), client.fallbackTarget())));
            }
            for (Future<LlmClient.LlmCallResult> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS).fallbackUsed()).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(fallback.requestCount()).isPositive();
    }

    private void startServers() throws Exception {
        startServers(5_000);
    }

    private void startServers(int primaryTimeoutMs) throws Exception {
        primary = new FakeOpenAiServer();
        fallback = new FakeOpenAiServer();
        properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("primary-key");
        properties.setModel("primary-model");
        properties.setBaseUrl(primary.baseUrl());
        properties.setTimeoutMs(primaryTimeoutMs);
        fallbackProperties = new LlmFallbackProperties();
        fallbackProperties.setEnabled(true);
        fallbackProperties.setApiKey("fallback-key");
        fallbackProperties.setModel("fallback-model");
        fallbackProperties.setBaseUrl(fallback.baseUrl());
        fallbackProperties.setTimeoutMs(5_000);
        client = new LlmClient(properties, fallbackProperties, new ObjectMapper());
    }

    private static Prompt prompt() {
        UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        return new Prompt("system prompt", "user prompt with phone 0912 345 678",
                Map.of(1, id), Map.of("U1", sender),
                List.of(new SummaryParticipant(sender, "Name", 1)), SummaryLanguage.EN);
    }

    private static String validSummary(String overview) {
        return "{\"overview\":\"" + overview + "\",\"keyPoints\":[],\"decisions\":[],"
                + "\"actionItems\":[],\"questions\":[],\"topics\":[]}";
    }
}
