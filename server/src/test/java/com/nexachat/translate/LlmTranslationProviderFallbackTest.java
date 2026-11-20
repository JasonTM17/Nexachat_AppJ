package com.nexachat.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import com.nexachat.summary.llm.LlmFallbackProperties;
import com.nexachat.summary.llm.LlmProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Breaker accounting on the translate provider chain: one primary attempt
 * records at most one breaker outcome. A primary failure whose local tier
 * then returns a blank completion used to record two failures for one
 * attempt, opening the breaker after three requests instead of five, and a
 * local-only run (no cloud target) used to record failures even though the
 * breaker gates the primary tier only.
 */
class LlmTranslationProviderFallbackTest {

    private FakeOpenAiServer primary;
    private FakeOpenAiServer local;
    private LlmProperties properties;
    private LlmFallbackProperties fallbackProperties;
    private LlmClient client;
    private LlmTranslationProvider provider;
    private CircuitBreaker circuitBreaker;

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (primary != null) {
            primary.close();
        }
        if (local != null) {
            local.close();
        }
    }

    @Test
    void blankLocalCompletionAfterPrimaryFailureCountsOneBreakerFailure() throws Exception {
        startServers(true);
        primary.handle((exchange, body, authorization) ->
                FakeOpenAiServer.respond(exchange, 500, "primary down"));
        local.handle((exchange, body, authorization) ->
                FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse("")));

        // Four attempts of one recorded failure each keep the breaker closed;
        // with double counting it would already be open here.
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> provider.translate(prompt()))
                    .isInstanceOf(TranslationUnavailableException.class);
            assertThat(circuitBreaker.allowRequest())
                    .as("breaker still closed after %d attempt(s)", attempt + 1)
                    .isTrue();
        }

        // The fifth attempt records the fifth failure and opens the breaker.
        assertThatThrownBy(() -> provider.translate(prompt()))
                .isInstanceOf(TranslationUnavailableException.class);

        assertThat(circuitBreaker.allowRequest()).isFalse();
    }

    @Test
    void blankLocalCompletionWithoutCloudNeverTouchesTheBreaker() throws Exception {
        startServers(false);
        local.handle((exchange, body, authorization) ->
                FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse("")));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> provider.translate(prompt()))
                    .isInstanceOf(TranslationUnavailableException.class);
        }

        assertThat(circuitBreaker.allowRequest())
                .as("a local-only run never records a primary-tier breaker outcome")
                .isTrue();
    }

    private void startServers(boolean cloudEnabled) throws Exception {
        primary = new FakeOpenAiServer();
        local = new FakeOpenAiServer();
        properties = new LlmProperties();
        properties.setEnabled(cloudEnabled);
        properties.setApiKey("primary-key");
        properties.setModel("primary-model");
        properties.setBaseUrl(primary.baseUrl());
        properties.setTimeoutMs(5_000);
        fallbackProperties = new LlmFallbackProperties();
        fallbackProperties.setEnabled(false);
        properties.setLocalEnabled(true);
        properties.setLocalModel("qwen2.5:3b");
        properties.setLocalBaseUrl(local.baseUrl());
        circuitBreaker = new CircuitBreaker(Clock.systemUTC());
        client = new LlmClient(properties, fallbackProperties, new ObjectMapper());
        provider = new LlmTranslationProvider(client, circuitBreaker);
    }

    private static TranslationPrompt prompt() {
        return new TranslationPrompt("system", "user", "{}", Map.of(), List.of());
    }
}
