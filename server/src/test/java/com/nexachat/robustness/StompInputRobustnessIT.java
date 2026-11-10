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
 * Hostile STOMP payloads on every {@code @MessageMapping} destination; the
 * session must stay up and errors use {@code WsErrorResponse} without leaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class StompInputRobustnessIT {

    private static final long TIMEOUT_SECONDS = 10;

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
    void everyMessageMappingSurvivesMalformedPayloads() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();

        List<String> destinations = List.of(
                "/app/conversations/" + conversationId + "/messages",
                "/app/conversations/" + conversationId + "/typing",
                "/app/conversations/" + conversationId + "/read",
                "/app/conversations/" + conversationId + "/delivered",
                "/app/chat.delivery.ack",
                "/app/chat.read.ack",
                "/app/calls/" + conversationId + "/signal");

        List<String> bodies = List.of(
                "not-json",
                "null",
                "[]",
                "",
                "{\"clientMessageId\":\"bad\",\"type\":\"TEXT\",\"content\":\"x\"}",
                "{\"upToSeq\":-1}");

        for (String destination : destinations) {
            for (String body : bodies) {
                sendExpectValidationOrRejection(destination, body, errors);
            }
        }
        assertThat(session.isConnected()).isTrue();
    }

    private void sendExpectValidationOrRejection(
            String destination, String body, BlockingQueue<WsErrorResponse> errors) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, body.getBytes(StandardCharsets.UTF_8));
        WsErrorResponse error = errors.poll(2, TimeUnit.SECONDS);
        if (error != null) {
            assertThat(error.code()).isIn("VALIDATION_FAILED", "NOT_MEMBER", "UNAUTHORIZED", "RATE_LIMITED");
            assertThat(objectMapper.writeValueAsString(error))
                    .doesNotContain("stackTrace", "SQLException", "at com.nexachat");
        }
        assertThat(session.isConnected()).isTrue();
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
