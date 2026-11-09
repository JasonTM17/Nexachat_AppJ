package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.summary.ProviderResult;
import com.nexachat.summary.SummaryComplexityAssessor;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryGeneration;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryMessage;
import com.nexachat.summary.SummaryProviderException;
import com.nexachat.summary.SummaryProviderSelector;
import com.nexachat.summary.SummaryWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSummaryProviderFallbackTest {

    private FakeOpenAiServer primary;
    private FakeOpenAiServer fallback;
    private LlmProperties properties;
    private LlmFallbackProperties fallbackProperties;
    private LlmClient client;
    private CircuitBreaker circuitBreaker;

    @AfterEach
    void closeResources() {
        SecurityContextHolder.clearContext();
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
    void primaryFailureFallsBackAndLabelsTheResult() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback overview."))));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LLM");
        assertThat(result.provider()).isEqualTo("fallback");
        assertThat(result.model()).isEqualTo("fallback-model");
        assertThat(local.calls).isZero();
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void primaryAuthFailureDoesNotFallBackAndDegradesToLocal() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
        assertThat(result.provider()).isNull();
        assertThat(fallback.requestCount()).isZero();
        assertThat(local.calls).isEqualTo(1);
    }

    @Test
    void bothProvidersFailingDegradeToLocal() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 503, "fallback down"));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
        assertThat(result.provider()).isNull();
        assertThat(local.calls).isEqualTo(1);
    }

    @Test
    void disabledFallbackDegradesToLocal() throws Exception {
        startServers();
        fallbackProperties.setEnabled(false);
        rebuildClient();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
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
                FakeOpenAiServer.jsonResponse(validSummary("Fallback overview."))));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LLM");
        assertThat(result.provider()).isEqualTo("fallback");
        assertThat(primary.requestCount()).isZero();
        assertThat(fallback.requestCount()).isPositive();
    }

    @Test
    void openCircuitWithoutFallbackDegradesToLocal() throws Exception {
        startServers();
        fallbackProperties.setEnabled(false);
        rebuildClient();
        for (int index = 0; index < 5; index++) {
            circuitBreaker.recordFailure();
        }
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

        assertThat(result.generatedBy()).isEqualTo("LOCAL");
        assertThat(result.fallbackReason()).isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
        assertThat(primary.requestCount()).isZero();
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
            rebuildClient();
            primary.handle((exchange, body, authorization) ->
                    FakeOpenAiServer.respond(exchange, 500, "primary down"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse(validSummary("Local tier overview."))));
            LlmSummaryProvider llm = complexProvider();

            for (int index = 0; index < 5; index++) {
                ProviderResult result = llm.summarize(window(), SummaryLanguage.EN);
                assertThat(result.provider()).isNull();
                assertThat(result.model()).isEqualTo("qwen2.5:3b");
            }

            assertThat(circuitBreaker.allowRequest()).isFalse();

            int primaryCallsBefore = primary.requestCount();
            ProviderResult result = llm.summarize(window(), SummaryLanguage.EN);

            assertThat(result.provider()).isNull();
            assertThat(result.model()).isEqualTo("qwen2.5:3b");
            assertThat(primary.requestCount()).isEqualTo(primaryCallsBefore);
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
    }

    @Test
    void openCircuitWithoutFallbackStillUsesTheLocalTier() throws Exception {
        startServers();
        FakeOpenAiServer local = new FakeOpenAiServer();
        try {
            fallbackProperties.setEnabled(false);
            properties.setLocalEnabled(true);
            properties.setLocalModel("qwen2.5:3b");
            properties.setLocalBaseUrl(local.baseUrl());
            rebuildClient();
            for (int index = 0; index < 5; index++) {
                circuitBreaker.recordFailure();
            }
            primary.handle((exchange, body, authorization) ->
                    FakeOpenAiServer.respond(exchange, 500, "primary down"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse(validSummary("Local tier overview."))));
            LlmSummaryProvider llm = complexProvider();

            ProviderResult result = llm.summarize(window(), SummaryLanguage.EN);

            assertThat(result.provider()).isNull();
            assertThat(result.model()).isEqualTo("qwen2.5:3b");
            assertThat(primary.requestCount()).isZero();
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
    }

    @Test
    void fallbackReceivesTheSameRedactedPrompt() throws Exception {
        startServers();
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse(validSummary("Fallback overview."))));
        LlmSummaryProvider llm = provider();
        StubLocalProvider local = new StubLocalProvider();
        SummaryProviderSelector selector = selector(local, llm);

        selector.generate(selector.prepare(true), windowWithPhone(), SummaryLanguage.EN);

        String primaryBody = primary.requests().get(0).body();
        String fallbackBody = fallback.requests().get(0).body();
        assertThat(fallbackBody).doesNotContain("0912 345 678");
        assertThat(primaryBody).doesNotContain("0912 345 678");
        assertThat(primaryBody).contains("[REDACTED_PHONE]");
        assertThat(fallbackBody).contains("[REDACTED_PHONE]");
        assertThat(messagesOf(primaryBody)).isEqualTo(messagesOf(fallbackBody));
    }

    @Test
    void revokedConsentPreventsBothProviders() throws Exception {
        startServers();
        UUID caller = UUID.randomUUID();
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(caller)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(caller), null, List.of()));
        LlmSummaryProvider llm = provider(properties, client, consentRepository, circuitBreaker);

        assertThatThrownBy(() -> llm.summarize(window(), SummaryLanguage.EN))
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.NO_CONSENT);
        assertThat(primary.requestCount()).isZero();
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void fallbackWithoutAConfiguredPrimaryIsNeverCalled() throws Exception {
        startServers();
        properties.setEnabled(false);
        rebuildClient();
        LlmSummaryProvider llm = provider();

        assertThatThrownBy(() -> llm.summarize(window(), SummaryLanguage.EN))
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.LLM_DISABLED);
        assertThat(fallback.requestCount()).isZero();
    }

    @Test
    void secondaryLocalTierAnsweringIsNotLabelledPrimaryOrFallback() throws Exception {
        startServers();
        FakeOpenAiServer local = new FakeOpenAiServer();
        try {
            properties.setLocalEnabled(true);
            properties.setLocalModel("qwen2.5:3b");
            properties.setLocalBaseUrl(local.baseUrl());
            rebuildClient();
            primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 401, "bad key"));
            local.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                    FakeOpenAiServer.jsonResponse(validSummary("Local tier overview."))));
            LlmSummaryProvider llm = complexProvider();
            StubLocalProvider stub = new StubLocalProvider();
            SummaryProviderSelector selector = selector(stub, llm);

            SummaryGeneration result = selector.generate(selector.prepare(true), window(), SummaryLanguage.EN);

            assertThat(result.generatedBy()).isEqualTo("LLM");
            assertThat(result.provider()).isNull();
            assertThat(result.model()).isEqualTo("qwen2.5:3b");
            assertThat(local.requestCount()).isPositive();
        } finally {
            local.close();
        }
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
        circuitBreaker = new CircuitBreaker(Clock.systemUTC());
        rebuildClient();
        UUID caller = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(caller), null, List.of()));
    }

    private void rebuildClient() {
        if (client != null) {
            client.close();
        }
        client = new LlmClient(properties, fallbackProperties, new ObjectMapper());
    }

    private LlmSummaryProvider provider() {
        UUID caller = ((JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(caller)).thenReturn(true);
        return provider(properties, client, consentRepository, circuitBreaker);
    }

    private LlmSummaryProvider complexProvider() {
        UUID caller = ((JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
        SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
        when(consentRepository.isConsented(caller)).thenReturn(true);
        return new LlmSummaryProvider(properties, client, new PromptBuilder(new ObjectMapper(), new Redactor()),
                new LlmOutputValidator(), circuitBreaker, consentRepository,
                new SummaryComplexityAssessor(0, 0, 0));
    }

    private LlmSummaryProvider provider(
            LlmProperties properties, LlmClient client, SummaryConsentRepository consentRepository, CircuitBreaker breaker) {
        return new LlmSummaryProvider(properties, client, new PromptBuilder(new ObjectMapper(), new Redactor()),
                new LlmOutputValidator(), breaker, consentRepository);
    }

    private SummaryProviderSelector selector(StubLocalProvider local, LlmSummaryProvider llm) {
        return new SummaryProviderSelector(List.of(local, llm), "auto", true, "test-only-key");
    }

    private static String messagesOf(String requestBody) throws Exception {
        return new ObjectMapper().readTree(requestBody).path("messages").toString();
    }

    private static SummaryWindow window() {
        UUID sender = UUID.randomUUID();
        return new SummaryWindow(UUID.randomUUID(), 0, 0, List.of(
                message(sender, 1, "The team will use a local summary."),
                message(sender, 2, "The group needs to test the endpoint."),
                message(sender, 3, "The next step is to review privacy."),
                message(sender, 4, "We will send the update tomorrow."),
                message(sender, 5, "The team agreed to continue.")));
    }

    private static SummaryWindow windowWithPhone() {
        UUID sender = UUID.randomUUID();
        return new SummaryWindow(UUID.randomUUID(), 0, 0, List.of(
                message(sender, 1, "Call me at 0912 345 678 tomorrow."),
                message(sender, 2, "The group needs to test the endpoint."),
                message(sender, 3, "The next step is to review privacy."),
                message(sender, 4, "We will send the update tomorrow."),
                message(sender, 5, "The team agreed to continue.")));
    }

    private static SummaryMessage message(UUID sender, long seq, String text) {
        return new SummaryMessage(UUID.randomUUID(), seq, sender, "Private sender name", text, Instant.EPOCH, null);
    }

    private static String validSummary(String overview) {
        return "{\"overview\":\"" + overview + "\",\"keyPoints\":[],\"decisions\":[],"
                + "\"actionItems\":[],\"questions\":[],\"topics\":[]}";
    }

    private static final class StubLocalProvider implements com.nexachat.summary.SummaryProvider {
        private int calls;

        @Override
        public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
            calls++;
            return new ProviderResult(new com.nexachat.summary.SummaryContent("Local overview.",
                    List.of(new com.nexachat.summary.SummaryItem("Local point.", List.of(window.messages().get(0).id()))),
                    List.of(), List.of(), List.of(), List.of("Local"),
                    List.of(new com.nexachat.summary.SummaryParticipant(window.messages().get(0).senderId(), "Sender", 5))),
                    SummaryLanguage.EN, null);
        }

        @Override
        public String id() {
            return "LOCAL";
        }
    }
}
