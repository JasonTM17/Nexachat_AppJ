package com.nexachat.robustness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.ws.dto.WsErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hostile STOMP payloads on every {@code @MessageMapping} destination.
 *
 * <p>Every destination that declares a JSON payload must answer a malformed
 * body with a {@link WsErrorResponse} and no stored row; the session must stay
 * up. {@code /app/conversations/{id}/typing} declares no payload, so a body is
 * ignored by contract — the assertion there is "no error, no row, session
 * alive", not an invented rejection.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class StompInputRobustnessIT {

    private static final long TIMEOUT_SECONDS = 10;
    private static final long ERROR_TIMEOUT_SECONDS = 5;

    private static final List<String> MALFORMED_BODIES = List.of(
            "not-json",
            "null",
            "[]",
            "",
            "{\"clientMessageId\":\"bad\",\"type\":\"TEXT\",\"content\":\"x\"}",
            "{\"upToSeq\":-1}");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accessToken;
    private UUID conversationId;
    private WebSocketStompClient client;
    private StompSession session;
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void connect() throws Exception {
        String phone = "+8498" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        var reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", "Stomp Fuzz"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(reg.getResponse().getContentAsString());
        accessToken = body.get("accessToken").asText();
        UUID userId = UUID.fromString(body.get("user").get("id").asText());

        String peerPhone = "+8498" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        var peerReg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", peerPhone, "password", "secret123", "displayName", "Peer"))))
                .andExpect(status().isCreated()).andReturn();
        UUID peerId = UUID.fromString(objectMapper.readTree(
                peerReg.getResponse().getContentAsString()).get("user").get("id").asText());

        var direct = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", peerId.toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        conversationId = UUID.fromString(objectMapper.readTree(
                direct.getResponse().getContentAsString()).get("id").asText());

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(new ConcurrentTaskScheduler(stompTaskScheduler));
        client.setDefaultHeartbeat(new long[]{0, 0});
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);
        session = client.connectAsync("ws://localhost:" + port + "/ws",
                        (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (client != null) {
            client.stop();
        }
        stompTaskScheduler.shutdownNow();
    }

    @Test
    void messageDestinationRejectsEveryMalformedBodyAndStoresNothing() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();
        long before = messageCount();

        sendAndAwaitRejection("/app/conversations/" + conversationId + "/messages", errors);

        assertThat(messageCount()).isEqualTo(before);
        assertNoStrayError(errors);
    }

    @Test
    void watermarkDestinationsRejectEveryMalformedBody() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();
        long before = messageCount();

        for (String destination : List.of(
                "/app/conversations/" + conversationId + "/read",
                "/app/conversations/" + conversationId + "/delivered",
                "/app/chat.delivery.ack",
                "/app/chat.read.ack")) {
            sendAndAwaitRejection(destination, errors);
        }

        assertThat(messageCount()).isEqualTo(before);
        assertNoStrayError(errors);
    }

    @Test
    void callSignallingRejectsEveryMalformedBody() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();

        sendAndAwaitRejection("/app/calls/" + conversationId + "/signal", errors);

        assertNoStrayError(errors);
    }

    /**
     * Typing carries no payload in the frozen contract, so malformed JSON must
     * be ignored rather than invented into a validation failure. The only
     * observable requirements: no stored message, no error frame, session up.
     */
    @Test
    void typingDestinationIgnoresBodyWithoutStoringAnything() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();
        long before = messageCount();

        for (String body : MALFORMED_BODIES) {
            send("/app/conversations/" + conversationId + "/typing", body);
        }

        assertThat(session.isConnected()).isTrue();
        assertThat(messageCount()).isEqualTo(before);
        assertThat(errors.poll(500, TimeUnit.MILLISECONDS))
                .as("typing has no payload contract and must not emit an error")
                .isNull();
    }

    private void sendAndAwaitRejection(String destination, BlockingQueue<WsErrorResponse> errors)
            throws Exception {
        for (String body : MALFORMED_BODIES) {
            send(destination, body);
            WsErrorResponse error = errors.poll(ERROR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(error)
                    .as("%s must reject body %s", destination, describe(body))
                    .isNotNull();
            assertThat(error.code())
                    .as("%s error code for body %s", destination, describe(body))
                    .isEqualTo("VALIDATION_FAILED");
            assertThat(objectMapper.writeValueAsString(error))
                    .doesNotContain("stackTrace", "SQLException", "at com.nexachat");
        }
        assertThat(session.isConnected()).isTrue();
    }

    private void send(String destination, String body) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private void assertNoStrayError(BlockingQueue<WsErrorResponse> errors) throws InterruptedException {
        assertThat(errors.poll(250, TimeUnit.MILLISECONDS))
                .as("exactly one error per rejected frame")
                .isNull();
    }

    private long messageCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Long.class, conversationId);
        return count == null ? 0L : count;
    }

    private static String describe(String body) {
        return body.isEmpty() ? "<empty>" : body;
    }

    private BlockingQueue<WsErrorResponse> subscribeErrors() {
        BlockingQueue<WsErrorResponse> errors = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/user/queue/errors");
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return WsErrorResponse.class;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                errors.add((WsErrorResponse) payload);
            }
        });
        return errors;
    }
}
