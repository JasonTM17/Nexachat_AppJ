package com.nexachat.summary.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryFallbackReason;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {

    private HttpServer server;
    private LlmClient client;

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsJsonModeRequestAndIgnoresReasoningContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\","
                    + "\"reasoning_content\":\"must-not-be-returned\"}}]}");
        });
        client = new LlmClient(properties(5_000), new ObjectMapper());

        String content = client.complete(prompt());

        assertThat(content).isEqualTo("{\"ok\":true}");
        assertThat(content).doesNotContain("must-not-be-returned");
        assertThat(authHeader.get()).isEqualTo("Bearer test-only-key");
        assertThat(requestBody.get()).contains("\"model\":\"test-model\"", "\"max_tokens\":4000",
                "\"response_format\":{\"type\":\"json_object\"}", "\"role\":\"system\"");
        assertThat(requestBody.get()).doesNotContain("test-only-key");
    }

    @Test
    void retriesOneServerErrorWithinTheRequestBudget() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 500, "ignored");
            } else {
                respond(exchange, 200, response("{\"ok\":true}"));
            }
        });
        client = new LlmClient(properties(5_000), new ObjectMapper());

        assertThat(client.complete(prompt())).isEqualTo("{\"ok\":true}");
        assertThat(requests).hasValue(2);
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429, "ignored");
        });
        client = new LlmClient(properties(5_000), new ObjectMapper());

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(SummaryProviderException.class)
                .satisfies(error -> {
                    assertThat(((SummaryProviderException) error).fallbackReason())
                            .isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
                    assertThat(error.getMessage()).doesNotContain("test-only-key");
                });
        assertThat(requests).hasValue(1);
    }

    @Test
    void boundsResponseBodyAndMapsSlowServerToTimeoutWithoutLeakingSecrets() throws Exception {
        startServer(exchange -> respond(exchange, 200, response("x".repeat(256 * 1024 + 1))));
        client = new LlmClient(properties(5_000), new ObjectMapper());
        assertBadOutput(() -> client.complete(prompt()));

        client.close();
        client = null;
        server.stop(0);
        startServer(exchange -> {
            try {
                Thread.sleep(1_000);
                respond(exchange, 200, response("{\"ok\":true}"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        client = new LlmClient(properties(200), new ObjectMapper());

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.LLM_TIMEOUT);
    }

    @Test
    void reportsUsageTokensAndStillIgnoresReasoningContent() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45},"
                        + "\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\","
                        + "\"reasoning_content\":\"must-not-be-returned\"}}]}"));
        client = new LlmClient(properties(5_000), new ObjectMapper());

        LlmClient.LlmCompletion completion = client.completeWithUsage(prompt());

        assertThat(completion.content()).isEqualTo("{\"ok\":true}");
        assertThat(completion.promptTokens()).isEqualTo(123);
        assertThat(completion.completionTokens()).isEqualTo(45);
    }

    @Test
    void malformedContentMapsToBadOutput() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":42}}]}"));
        client = new LlmClient(properties(5_000), new ObjectMapper());

        assertBadOutput(() -> client.complete(prompt()));
    }

    @Test
    void retriesOneServerErrorAndStopsAfterTwoAttempts() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        client = new LlmClient(properties(5_000), new ObjectMapper());

        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.LLM_HTTP_ERROR);
        assertThat(requests).hasValue(2);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private LlmProperties properties(int timeoutMs) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-only-key");
        properties.setModel("test-model");
        properties.setTimeoutMs(timeoutMs);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return properties;
    }

    private static Prompt prompt() {
        UUID id = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        return new Prompt("system prompt", "user prompt", java.util.Map.of(1, id), java.util.Map.of("U1", sender),
                List.of(new com.nexachat.summary.SummaryParticipant(sender, "Name", 1)), SummaryLanguage.EN);
    }

    private static String response(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "}}]}";
    }

    private static String quote(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void assertBadOutput(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SummaryProviderException.class)
                .extracting("fallbackReason").isEqualTo(SummaryFallbackReason.LLM_BAD_OUTPUT);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
