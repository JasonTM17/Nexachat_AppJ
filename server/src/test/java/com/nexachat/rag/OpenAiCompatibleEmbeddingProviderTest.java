package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleEmbeddingProviderTest {

    private HttpServer server;
    private volatile byte[] requestBody;
    private volatile Map<String, String> requestHeaders;
    private volatile String requestPath;
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            requestHeaders = headers;
            requestPath = exchange.getRequestURI().getPath();
            byte[] payload = responsePayload();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsOpenAiShapeWithTheKeyOnlyInTheAuthorizationHeader() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false);

        float[] vector = provider.embed("hello");

        assertThat(vector).hasSize(256);
        String body = new String(requestBody, StandardCharsets.UTF_8);
        assertThat(body).contains("\"model\":\"gemini-embedding-001\"");
        assertThat(body).contains("\"dimensions\":256");
        assertThat(body).contains("\"input\":\"hello\"");
        assertThat(body).doesNotContain("secret-key");
        assertThat(requestHeaders.get("authorization")).isEqualTo("Bearer secret-key");
        assertThat(requestPath).endsWith("/embeddings");
    }

    @Test
    void sendsGeminiShapeWithOutputDimensionality() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, true);

        float[] vector = provider.embed("hello");

        assertThat(vector).hasSize(256);
        String body = new String(requestBody, StandardCharsets.UTF_8);
        assertThat(body).contains("\"outputDimensionality\":256");
        assertThat(body).contains("\"parts\"");
        assertThat(body).doesNotContain("secret-key");
        assertThat(requestHeaders.get("x-goog-api-key")).isEqualTo("secret-key");
        assertThat(requestPath).endsWith("/models/gemini-embedding-001:embedContent");
    }

    @Test
    void rejectsNonLoopbackHttpBaseUrls() {
        assertThatThrownBy(() -> new OpenAiCompatibleEmbeddingProvider(
                "http://example.com", "model", "key", 256, 1_000, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorResponsesNeverExposeTheApiKey() {
        status = 500;
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false);

        assertThatThrownBy(() -> provider.embed("hello"))
                .isInstanceOf(EmbeddingProviderException.class)
                .hasMessageNotContaining("secret-key");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private byte[] responsePayload() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode values = mapper.createArrayNode();
            for (int index = 0; index < 256; index++) {
                values.add(0.01 * (index + 1));
            }
            ObjectNode root = mapper.createObjectNode();
            if (requestPath != null && requestPath.endsWith(":embedContent")) {
                root.putObject("embedding").set("values", values);
            } else {
                root.putArray("data").addObject().set("embedding", values);
            }
            return mapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
