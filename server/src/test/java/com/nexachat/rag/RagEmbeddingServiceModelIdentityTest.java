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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagEmbeddingServiceModelIdentityTest {

    private static final int DIM = 16;
    private static final String MODEL = "gemini-embedding-001";
    private static final String MODEL_IDENTITY = "openai-compatible:" + MODEL;
    private static final String OLD_IDENTITY = "openai-compatible";

    private HttpServer server;
    private RagProperties properties;
    private InMemoryRagMessageIndex index;
    private VectorCache cache;
    private RagEmbeddingService embeddings;
    private volatile float[] responseVector;
    private volatile int status = 200;
    private volatile int requestCount;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requestCount++;
            byte[] payload = responsePayload();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        properties = new RagProperties();
        properties.getEmbedding().setProvider("openai-compatible");
        properties.getEmbedding().setMode("openai-compatible");
        properties.getEmbedding().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getEmbedding().setModel(MODEL);
        properties.getEmbedding().setApiKey("test-key");
        properties.getEmbedding().setDim(DIM);
        properties.getEmbedding().setTimeoutMs(5_000);
        index = new InMemoryRagMessageIndex();
        cache = new VectorCache(1024);
        RagEmbeddingProviders providers = new RagEmbeddingProviders(properties);
        embeddings = new RagEmbeddingService(
                providers, index, cache, properties, new RagStats(), new Redactor());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void embedQuestionReportsTheModelIdentity() {
        responseVector = vector(1.0f);

        RagEmbeddingService.EmbeddingOutcome outcome = embeddings.embedQuestion("hello", true);

        assertThat(outcome.model()).isEqualTo(MODEL_IDENTITY);
    }

    @Test
    void vectorsFromTheOldIdentityAreRecomputedInsteadOfReused() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RagMessage message = RagTestSupport.message(
                conversationId, messageId, 1, UUID.randomUUID(), "User",
                "The migration is scheduled for March 14.");
        index.add(message);

        // Rows written before the model was part of the identity: the provider
        // id alone is stored, with a hash that still matches the content.
        String embeddedInput = new Redactor().redact(message.content());
        String oldHash = RagHashes.embeddingInputHash(embeddedInput);
        index.saveVectors(OLD_IDENTITY, DIM, List.of(message),
                Map.of(messageId, vector(9.0f)), Map.of(messageId, oldHash));

        responseVector = vector(1.0f);
        RagEmbeddingService.VectorOutcome outcome = embeddings.ensureVectors(List.of(message), 10, true);

        assertThat(outcome.model()).isEqualTo(MODEL_IDENTITY);
        assertThat(outcome.vectors().get(messageId)).containsExactly(vector(1.0f));
        assertThat(index.loadVectors(MODEL_IDENTITY, List.of(messageId))).containsKey(messageId);
        // The stale row is left untouched; it is simply never read again.
        assertThat(index.loadVectors(OLD_IDENTITY, List.of(messageId))).containsKey(messageId);
        assertThat(requestCount).isPositive();
    }

    @Test
    void localFallbackThenExternalRecoveryDoesNotMixVectorSpaces() {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RagMessage message = RagTestSupport.message(
                conversationId, messageId, 1, UUID.randomUUID(), "User",
                "The migration is scheduled for March 14.");
        index.add(message);

        // The external provider is down: retrieval degrades to the local space
        // and caches local vectors.
        status = 500;
        RagEmbeddingService.RetrievalEmbeddings degraded =
                embeddings.embedForRetrieval("When is the migration?", List.of(message), 10, true);
        assertThat(degraded.model()).isEqualTo("lexical");
        float[] localVector = degraded.vectors().get(messageId);
        assertThat(localVector).isNotNull();

        // The external provider recovers: the cached local vector must not be
        // reused under the external model identity.
        responseVector = vector(1.0f);
        status = 200;
        RagEmbeddingService.RetrievalEmbeddings recovered =
                embeddings.embedForRetrieval("When is the migration?", List.of(message), 10, true);

        assertThat(recovered.model()).isEqualTo(MODEL_IDENTITY);
        assertThat(recovered.vectors().get(messageId)).isNotSameAs(localVector);
        assertThat(recovered.vectors().get(messageId)).containsExactly(vector(1.0f));
    }

    private byte[] responsePayload() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode values = mapper.createArrayNode();
            for (int index = 0; index < DIM; index++) {
                values.add(responseVector[index]);
            }
            ObjectNode root = mapper.createObjectNode();
            root.putArray("data").addObject().set("embedding", values);
            return mapper.writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static float[] vector(float value) {
        float[] vector = new float[DIM];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = value;
        }
        return vector;
    }
}
