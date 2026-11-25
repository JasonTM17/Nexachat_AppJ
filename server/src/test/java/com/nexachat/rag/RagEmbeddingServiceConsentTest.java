package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEmbeddingServiceConsentTest {

    private static final int DIM = 16;
    private static final String MODEL = "gemini-embedding-001";

    private HttpServer server;
    private RagProperties properties;
    private InMemoryRagMessageIndex index;
    private RagStats stats;
    private CircuitBreaker circuitBreaker;
    private SummaryConsentRepository consentRepository;
    private RagEmbeddingService embeddings;
    private AtomicInteger requestCount;
    private UUID userId;

    @BeforeEach
    void setUp() throws IOException {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requestCount.incrementAndGet();
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            int inputCount = 1;
            com.fasterxml.jackson.databind.JsonNode input = new ObjectMapper().readTree(requestBytes).path("input");
            if (input.isArray()) {
                inputCount = input.size();
            }
            byte[] payload = responsePayload(inputCount);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
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
        stats = new RagStats();
        circuitBreaker = new CircuitBreaker(Clock.systemUTC());
        consentRepository = mock(SummaryConsentRepository.class);
        userId = UUID.randomUUID();
        embeddings = new RagEmbeddingService(
                new RagEmbeddingProviders(properties),
                index,
                new VectorCache(1024),
                properties,
                stats,
                new Redactor(),
                circuitBreaker,
                consentRepository);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void embedQuestionAbortsBeforeExternalCallWhenConsentRevoked() {
        when(consentRepository.isConsented(userId)).thenReturn(false);

        RagEmbeddingService.EmbeddingOutcome outcome =
                embeddings.embedQuestion(userId, "What did we agree on?", true);

        assertThat(outcome.model()).isEqualTo("lexical");
        assertThat(requestCount.get()).isZero();
        assertThat(stats.snapshot().embeddingCalls()).isZero();
        assertThat(stats.snapshot().embeddingFailures()).isZero();
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    @Test
    void embedForRetrievalRevertsAllVectorsToLocalIfConsentRevokedBeforeBatchCall() {
        UUID conversationId = UUID.randomUUID();
        RagMessage message = RagTestSupport.message(
                conversationId, UUID.randomUUID(), 1, UUID.randomUUID(), "User",
                "Release freeze starts on Thursday.");
        index.add(message);
        // Question embedding sees consent=true; batch embedding sees consent=false (revoked mid-request).
        when(consentRepository.isConsented(userId)).thenReturn(true, false);

        RagEmbeddingService.RetrievalEmbeddings outcome = embeddings.embedForRetrieval(
                userId, "When does the freeze start?", List.of(message), 10, true);

        assertThat(outcome.model()).isEqualTo("lexical");
        assertThat(outcome.questionVector()).isNotEmpty();
        assertThat(outcome.vectors()).containsKey(message.id());
        assertThat(outcome.vectors().get(message.id())).hasSameSizeAs(outcome.questionVector());
        assertThat(requestCount.get())
                .as("external embedAll must not fire once live consent returns false")
                .isEqualTo(1);
        assertThat(stats.snapshot().embeddingFailures()).isZero();
        assertThat(circuitBreaker.allowRequest()).isTrue();
    }

    private static byte[] responsePayload(int inputCount) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode values = mapper.createArrayNode();
        for (int index = 0; index < DIM; index++) {
            values.add(1.0f);
        }
        ObjectNode root = mapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (int position = 0; position < inputCount; position++) {
            ObjectNode row = data.addObject();
            row.put("index", position);
            row.set("embedding", values);
        }
        return mapper.writeValueAsBytes(root);
    }
}
