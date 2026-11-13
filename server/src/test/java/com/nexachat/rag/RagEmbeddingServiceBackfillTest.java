package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexachat.summary.llm.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RagEmbeddingServiceBackfillTest {

    private static final int DIM = 16;
    private static final String MODEL = "gemini-embedding-001";

    private HttpServer server;
    private RagProperties properties;
    private InMemoryRagMessageIndex index;
    private RagEmbeddingService embeddings;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = responsePayload();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        properties = new RagProperties();
        properties.setEnabled(true);
        properties.getIndex().setBackfillEnabled(true);
        properties.getIndex().setBackfillRatePerMinute(50);
        properties.getEmbedding().setProvider("openai-compatible");
        properties.getEmbedding().setMode("openai-compatible");
        properties.getEmbedding().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getEmbedding().setModel(MODEL);
        properties.getEmbedding().setApiKey("test-key");
        properties.getEmbedding().setDim(DIM);
        properties.getEmbedding().setTimeoutMs(5_000);
        index = new InMemoryRagMessageIndex();
        RagEmbeddingProviders providers = new RagEmbeddingProviders(properties);
        embeddings = new RagEmbeddingService(
                providers, index, new VectorCache(1024), properties, new RagStats(), new Redactor());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void backfillNeverCallsExternalProviderWhenConfigured() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RagMessage message = RagTestSupport.message(
                conversationId, messageId, 1, UUID.randomUUID(), "User",
                "Secret thread content that must not leave the server on backfill.");
        index.add(message);

        int indexed = embeddings.backfill();

        assertThat(indexed).isPositive();
        assertThat(lastRequestBody.get()).isNull();
        assertThat(index.loadVectors(LocalLexicalEmbedding.ID, List.of(messageId))).containsKey(messageId);
        assertThat(index.loadVectors(providersExternalId(), List.of(messageId))).isEmpty();
    }

    private String providersExternalId() {
        return new RagEmbeddingProviders(properties).external().id();
    }

    private byte[] responsePayload() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode values = mapper.createArrayNode();
            for (int index = 0; index < DIM; index++) {
                values.add(1.0f);
            }
            ObjectNode root = mapper.createObjectNode();
            root.putArray("data").addObject().set("embedding", values);
            return mapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
