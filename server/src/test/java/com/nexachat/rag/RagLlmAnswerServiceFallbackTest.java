package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import com.nexachat.summary.llm.LlmFallbackProperties;
import com.nexachat.summary.llm.LlmProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagLlmAnswerServiceFallbackTest {

    private FakeOpenAiServer primary;
    private FakeOpenAiServer fallback;
    private LlmProperties properties;
    private LlmFallbackProperties fallbackProperties;
    private LlmClient client;
    private RagLlmAnswerService service;
    private RagStats stats;
    private CircuitBreaker circuitBreaker;
    private UUID userId;
    private UUID messageId;
    private UUID conversationId;

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
    void primaryFailureFallsBackAndLabelsTheAnswer() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNotNull();
        assertThat(answer.answer()).contains("March 14");
        assertThat(answer.citationIds()).contains(messageId);
        assertThat(answer.provider()).isEqualTo("fallback");
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void primarySuccessDoesNotCallTheFallback() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNotNull();
        assertThat(answer.provider()).isEqualTo("primary");
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void primaryAuthFailureDoesNotFallBackAndReturnsNull() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNull();
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void primaryDownWithHealthyLocalTierOpensTheBreakerButKeepsAnsweringLocally() throws Exception {
        startServers();
        FakeOpenAiServer local = new FakeOpenAiServer();
        try {
            fallbackProperties.setEnabled(false);
            properties.setLocalEnabled(true);
            properties.setLocalModel("qwen2.5:3b");
            properties.setLocalBaseUrl(local.baseUrl());
            rebuildService();
            primary.handle((exchange, body, authorization) ->
                    FakeOpenAiServer.respond(exchange, 500, "primary down"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

            for (int index = 0; index < 5; index++) {
                RagLlmAnswer answer = answer("When is the migration?");
                assertThat(answer).isNotNull();
                assertThat(answer.provider()).isNull();
            }

            assertThat(circuitBreaker.allowRequest()).isFalse();

            int primaryCallsBefore = primary.requestCount();
            RagLlmAnswer answer = answer("When is the migration?");

            assertThat(answer).isNotNull();
            assertThat(answer.provider()).isNull();
            assertThat(primary.requestCount()).isEqualTo(primaryCallsBefore);
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
    }

    @Test
    void openBreakerWithoutFallbackStillUsesTheLocalTier() throws Exception {
        startServers();
        FakeOpenAiServer local = new FakeOpenAiServer();
        try {
            fallbackProperties.setEnabled(false);
            properties.setLocalEnabled(true);
            properties.setLocalModel("qwen2.5:3b");
            properties.setLocalBaseUrl(local.baseUrl());
            rebuildService();
            for (int index = 0; index < 5; index++) {
                circuitBreaker.recordFailure();
            }
            primary.handle((exchange, body, authorization) ->
                    FakeOpenAiServer.respond(exchange, 500, "primary down"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

            RagLlmAnswer answer = answer("When is the migration?");

            assertThat(answer).isNotNull();
            assertThat(answer.provider()).isNull();
            assertThat(primary.requestCount()).isZero();
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
    }

    @Test
    void openBreakerWithoutFallbackOrLocalReturnsNull() throws Exception {
        startServers();
        fallbackProperties.setEnabled(false);
        rebuildService();
        for (int index = 0; index < 5; index++) {
            circuitBreaker.recordFailure();
        }
        primary.handle((exchange, body, authorization) ->
                FakeOpenAiServer.respond(exchange, 500, "primary down"));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNull();
        assertThat(primary.requestCount()).isZero();
    }

    @Test
    void bothProvidersFailingReturnsNull() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 503, "fallback down"));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNull();
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void disabledFallbackReturnsNull() throws Exception {
        startServers();
        fallbackProperties.setEnabled(false);
        rebuildService();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNull();
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void openCircuitUsesTheFallbackWithoutCallingThePrimary() throws Exception {
        startServers();
        for (int index = 0; index < 5; index++) {
            circuitBreaker.recordFailure();
        }
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNotNull();
        assertThat(answer.provider()).isEqualTo("fallback");
        assertThat(primary.requestCount()).isZero();
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void secondaryLocalTierAnsweringIsNotLabelledPrimaryOrFallback() throws Exception {
        startServers();
        FakeOpenAiServer local = new FakeOpenAiServer();
        try {
            properties.setLocalEnabled(true);
            properties.setLocalModel("qwen2.5:3b");
            properties.setLocalBaseUrl(local.baseUrl());
            rebuildService();
            primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

            RagLlmAnswer answer = answer("When is the migration?");

            assertThat(answer).isNotNull();
            assertThat(answer.provider()).isNull();
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
    }

    @Test
    void localTierStillAnswersWhenTheCloudPrimaryIsNotConfigured() throws Exception {
        startServers();
        properties.setEnabled(false);
        properties.setLocalEnabled(true);
        properties.setLocalModel("qwen2.5:3b");
        properties.setLocalBaseUrl(primary.baseUrl());
        rebuildService();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNotNull();
        assertThat(answer.provider()).isNull();
        assertThat(primary.requestCount()).isPositive();
    }

    @Test
    void revokedConsentReturnsNullWithoutAnyProviderCall() throws Exception {        startServers();
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(userId)).thenReturn(false);
        service = new RagLlmAnswerService(client, new RagPromptBuilder(new ObjectMapper(), new com.nexachat.summary.llm.Redactor()),
                new RagOutputValidator(new ObjectMapper()), circuitBreaker, consentRepository, stats);

        RagLlmAnswer answer = answer("When is the migration?");

        assertThat(answer).isNull();
        assertThat(primary.requestCount()).isZero();
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void fallbackReceivesTheSameRedactedPrompt() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        answer("When is the migration? Call 0912 345 678.");

        String primaryBody = primary.requests().get(0).body();
        String fallbackBody = fallback.requests().get(0).body();
        assertThat(primaryBody).doesNotContain("0912 345 678");
        assertThat(fallbackBody).doesNotContain("0912 345 678");
        assertThat(primaryBody).contains("[REDACTED_PHONE]");
        assertThat(fallbackBody).contains("[REDACTED_PHONE]");
        assertThat(messagesOf(primaryBody)).isEqualTo(messagesOf(fallbackBody));
    }

    @Test
    void concurrentFallbackCallsAllSucceed() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        int calls = 8;
        ExecutorService executor = Executors.newFixedThreadPool(calls);
        try {
            List<Future<RagLlmAnswer>> results = new java.util.ArrayList<>();
            for (int index = 0; index < calls; index++) {
                results.add(executor.submit(() ->
                        answer("When is the migration?")));
            }
            for (Future<RagLlmAnswer> result : results) {
                RagLlmAnswer answer = result.get(15, TimeUnit.SECONDS);
                assertThat(answer).isNotNull();
                assertThat(answer.provider()).isEqualTo("fallback");
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(fallback.requestCount()).isPositive();
    }

    private void startServers() throws Exception {
        primary = new FakeOpenAiServer();
        fallback = new FakeOpenAiServer();
        properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("primary-key");
        properties.setModel("primary-model");
        properties.setBaseUrl(primary.baseUrl());
        properties.setTimeoutMs(5_000);
        fallbackProperties = new LlmFallbackProperties();
        fallbackProperties.setEnabled(true);
        fallbackProperties.setApiKey("fallback-key");
        fallbackProperties.setModel("fallback-model");
        fallbackProperties.setBaseUrl(fallback.baseUrl());
        fallbackProperties.setTimeoutMs(5_000);
        stats = new RagStats();
        circuitBreaker = new CircuitBreaker(Clock.systemUTC());
        userId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        conversationId = UUID.randomUUID();
        rebuildService();
    }

    private void rebuildService() {
        if (client != null) {
            client.close();
        }
        client = new LlmClient(properties, fallbackProperties, new ObjectMapper());
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(userId)).thenReturn(true);
        service = new RagLlmAnswerService(client, new RagPromptBuilder(new ObjectMapper(), new com.nexachat.summary.llm.Redactor()),
                new RagOutputValidator(new ObjectMapper()), circuitBreaker, consentRepository, stats);
    }

    private static String messagesOf(String requestBody) throws Exception {
        return new ObjectMapper().readTree(requestBody).path("messages").toString();
    }

    private List<RagContextItem> context() {
        return List.of(new RagContextItem(1, List.of(messageId), 1, "U1",
                "The server migration is scheduled for March 14 at 02:00. Call 0912 345 678."));
    }

    private RagLlmAnswer answer(String question) {
        return service.answer(userId, conversationId, question, SummaryLanguage.EN, context(), contextMessages());
    }

    private java.util.Map<UUID, RagMessage> contextMessages() {
        return java.util.Map.of(messageId, new RagMessage(messageId, conversationId, 1, UUID.randomUUID(),
                "User", "The server migration is scheduled for March 14 at 02:00. Call 0912 345 678.",
                java.time.Instant.EPOCH, null, null));
    }
}
