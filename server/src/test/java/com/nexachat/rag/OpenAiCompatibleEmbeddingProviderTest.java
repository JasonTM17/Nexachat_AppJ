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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleEmbeddingProviderTest {

    private HttpServer server;
    private volatile byte[] requestBody;
    private volatile Map<String, String> requestHeaders;
    private volatile String requestPath;
    private volatile int status = 200;
    private volatile int batchStatus = -1;
    private volatile boolean malformedBatch;
    private volatile boolean reverseBatchOrder;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody = exchange.getRequestBody().readAllBytes();
            requestBodies.add(new String(requestBody, StandardCharsets.UTF_8));
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            requestHeaders = headers;
            requestPath = exchange.getRequestURI().getPath();
            byte[] payload = responsePayload();
            boolean batchRequest = requestPath != null && requestPath.endsWith(":batchEmbedContents")
                    || requestBodies.get(requestBodies.size() - 1).contains("\"input\":[");
            int responseStatus = batchRequest && batchStatus >= 0 ? batchStatus : status;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, payload.length);
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
    void idIncludesTheConfiguredModel() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false);

        assertThat(provider.id()).isEqualTo("openai-compatible:gemini-embedding-001");
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
    void openAiBatchUsesOneRequestPerConfiguredChunk() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false, 2);

        List<float[]> vectors = provider.embedAll(List.of("alpha", "beta", "gamma"));

        assertThat(vectors).hasSize(3);
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).contains("\"input\":[\"alpha\",\"beta\"]");
        assertThat(requestBodies.get(1)).contains("\"input\":[\"gamma\"]");
        assertThat(requestPath).endsWith("/embeddings");
    }

    @Test
    void geminiBatchUsesBatchEmbedContents() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, true, 32);

        List<float[]> vectors = provider.embedAll(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(1)[0]).isEqualTo(2.0f);
        assertThat(requestBodies).hasSize(1);
        assertThat(requestBodies.get(0)).contains("\"model\":\"models/gemini-embedding-001\"");
        assertThat(requestBodies.get(0)).contains("\"outputDimensionality\":256");
        assertThat(requestPath).endsWith("/models/gemini-embedding-001:batchEmbedContents");
    }

    @Test
    void rejectedBatchShapeFallsBackToSerialRequests() {
        batchStatus = 400;
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false, 32);

        List<float[]> vectors = provider.embedAll(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(requestBodies).hasSize(3);
    }

    @Test
    void malformedSuccessfulBatchFallsBackToSerialRequests() {
        malformedBatch = true;
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false, 32);

        List<float[]> vectors = provider.embedAll(List.of("alpha", "beta"));

        assertThat(vectors).hasSize(2);
        assertThat(requestBodies).hasSize(3);
    }

    @Test
    void serverFailureDoesNotMultiplyIntoSerialRequests() {
        batchStatus = 500;
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false, 32);

        assertThatThrownBy(() -> provider.embedAll(List.of("alpha", "beta")))
                .isInstanceOf(EmbeddingProviderException.class);
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void openAiBatchRowsAreRestoredToInputOrderByIndex() {
        reverseBatchOrder = true;
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "gemini-embedding-001", "secret-key", 256, 5_000, false, 32);

        List<float[]> vectors = provider.embedAll(List.of("alpha", "beta"));

        assertThat(vectors.get(0)[0]).isEqualTo(1.0f);
        assertThat(vectors.get(1)[0]).isEqualTo(2.0f);
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
            ObjectNode root = mapper.createObjectNode();
            if (requestPath != null && requestPath.endsWith(":batchEmbedContents")) {
                int count = mapper.readTree(requestBody).path("requests").size();
                ArrayNode embeddings = root.putArray("embeddings");
                for (int position = 0; position < count; position++) {
                    embeddings.addObject().set("values", values(mapper, position + 1));
                }
            } else {
                com.fasterxml.jackson.databind.JsonNode body = mapper.readTree(requestBody);
                if (body.path("input").isArray()) {
                    ArrayNode data = root.putArray("data");
                    int count = body.path("input").size();
                    if (malformedBatch && count > 1) {
                        count--;
                    }
                    for (int offset = 0; offset < count; offset++) {
                        int position = reverseBatchOrder ? count - 1 - offset : offset;
                        ObjectNode row = data.addObject();
                        row.put("index", position);
                        row.set("embedding", values(mapper, position + 1));
                    }
                } else if (requestPath != null && requestPath.endsWith(":embedContent")) {
                    root.putObject("embedding").set("values", values(mapper, 1));
                } else {
                    root.putArray("data").addObject().set("embedding", values(mapper, 1));
                }
            }
            return mapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ArrayNode values(ObjectMapper mapper, double marker) {
        ArrayNode values = mapper.createArrayNode();
        values.add(marker);
        for (int index = 1; index < 256; index++) {
            values.add(0.01);
        }
        return values;
    }

}
