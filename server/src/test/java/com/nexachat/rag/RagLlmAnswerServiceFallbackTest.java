package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.summary.ConversationAiOptOuts;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    void namedSpeakerProviderUsesTheVerifiedQuoteContract() throws Exception {
        startServers();
        String quote = "The server migration is scheduled for March 14 at 02:00.";
        primary.handle((exchange, requestBody, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"" + quote + "\",\"citations\":[1]}")));

        RagLlmOutcome outcome = service.answerSpeakerQuote(userId, conversationId, "When is the server migration scheduled?",
                SummaryLanguage.EN, context(), contextMessages(), AskDeadline.start(5_000));

        assertThat(outcome.status()).isEqualTo(RagLlmOutcome.Status.ANSWER);
        assertThat(outcome.answer().answer()).isEqualTo(quote);
        assertThat(outcome.answer().citationIds()).containsExactly(messageId);
        assertThat(primary.requestCount()).isEqualTo(1);
        assertThat(messagesOf(primary.requests().getFirst().body()))
                .contains("verbatim", "U1").doesNotContain("0912 345 678", "\"priorTurns\":");
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void primaryAnsweringGarbageOpensTheBreakerAndCountsFailures() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("not the expected answer json")));

        for (int index = 0; index < 5; index++) {
            RagLlmAnswer answer = answer("When is the migration?");
            assertThat(answer).isNull();
        }

        assertThat(circuitBreaker.allowRequest())
                .as("a responding-but-broken primary must open the breaker")
                .isFalse();
        assertThat(stats.snapshot().llmFailures())
                .as("an unusable primary answer is a provider failure")
                .isEqualTo(5);
    }

    @Test
    void validPrimaryAbstentionDoesNotCountAsProviderFailure() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":null,\"citations\":[]}")));

        for (int index = 0; index < 5; index++) {
            RagLlmOutcome outcome = detailedAnswer("What unrelated fact was never mentioned?");
            assertThat(outcome.status()).isEqualTo(RagLlmOutcome.Status.ABSTAIN);
            assertThat(outcome.llmAttempted()).isTrue();
            assertThat(outcome.answer()).isNull();
        }

        assertThat(circuitBreaker.allowRequest())
                .as("a valid abstention is healthy provider behavior")
                .isTrue();
        assertThat(stats.snapshot().llmFailures()).isZero();
        assertThat(primary.requestCount()).isEqualTo(5);
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

        RagLlmOutcome outcome = detailedAnswer("When is the migration?");

        assertThat(outcome.status()).isEqualTo(RagLlmOutcome.Status.PROVIDER_ERROR);
        assertThat(outcome.llmAttempted()).isTrue();
        assertThat(outcome.answer()).isNull();
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

        RagLlmOutcome outcome = detailedAnswer("When is the migration?");

        assertThat(outcome.status()).isEqualTo(RagLlmOutcome.Status.PROVIDER_ERROR);
        assertThat(outcome.consentRequired()).isTrue();
        assertThat(outcome.llmAttempted()).isFalse();
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

    @Test
    void bulkheadRejectsSaturationWithoutCountingProviderFailure() throws Exception {
        startServers();
        CountDownLatch primaryEntered = new CountDownLatch(1);
        CountDownLatch releasePrimary = new CountDownLatch(1);
        primary.handle((exchange, body, authorization) -> {
            primaryEntered.countDown();
            try {
                if (!releasePrimary.await(5, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("timed out waiting for test release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(exception);
            }
            FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse(
                    "{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}"));
        });
        RagProperties ragProperties = new RagProperties();
        ragProperties.getLlm().setMaxConcurrent(1);
        ragProperties.getLlm().setBulkheadWaitMs(25);
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(userId)).thenReturn(true);
        service = new RagLlmAnswerService(client, new RagPromptBuilder(new ObjectMapper(), new com.nexachat.summary.llm.Redactor()),
                new RagOutputValidator(new ObjectMapper()), circuitBreaker, consentRepository, stats,
                ConversationAiOptOuts.none(), ragProperties);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RagLlmAnswer> first = executor.submit(() -> answer("When is the migration?"));
            assertThat(primaryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            RagLlmOutcome rejected = detailedAnswer("When is the migration?");

            assertThat(rejected.status()).isEqualTo(RagLlmOutcome.Status.BULKHEAD_REJECTED);
            assertThat(rejected.llmAttempted()).isFalse();
            assertThat(stats.snapshot().llmBulkheadRejections()).isEqualTo(1);
            assertThat(stats.snapshot().llmFailures()).isZero();
            releasePrimary.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releasePrimary.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void promptBuilderFailurePropagatesReleasesThePermitAndDoesNotCountProviderFailure() throws Exception {
        startServers();
        RagProperties ragProperties = new RagProperties();
        ragProperties.getLlm().setMaxConcurrent(1);
        ragProperties.getLlm().setBulkheadWaitMs(25);
        RagPromptBuilder throwingPromptBuilder = mock(RagPromptBuilder.class);
        when(throwingPromptBuilder.build(anyString(), any(SummaryLanguage.class), anyList(), anyList()))
                .thenThrow(new IllegalStateException("prompt boom"));
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(userId)).thenReturn(true);
        service = new RagLlmAnswerService(client, throwingPromptBuilder,
                new RagOutputValidator(new ObjectMapper()), circuitBreaker, consentRepository, stats,
                ConversationAiOptOuts.none(), ragProperties);

        assertThatThrownBy(() -> answer("When is the migration?"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prompt boom");
        assertThatThrownBy(() -> answer("When is the migration?"))
                .as("a second call must not block behind a leaked permit")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prompt boom");

        assertThat(stats.snapshot().llmBulkheadRejections()).isZero();
        assertThat(stats.snapshot().llmFailures()).isZero();
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

    private RagLlmOutcome detailedAnswer(String question) {
        return service.answerDetailed(userId, conversationId, question, SummaryLanguage.EN,
                context(), contextMessages(), List.of());
    }

    private java.util.Map<UUID, RagMessage> contextMessages() {
        return java.util.Map.of(messageId, new RagMessage(messageId, conversationId, 1, UUID.randomUUID(),
                "User", "The server migration is scheduled for March 14 at 02:00. Call 0912 345 678.",
                java.time.Instant.EPOCH, null, null));
    }
}
