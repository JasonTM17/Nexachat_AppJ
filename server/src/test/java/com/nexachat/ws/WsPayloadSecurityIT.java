package com.nexachat.ws;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.TestWaits;
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
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MimeType;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class WsPayloadSecurityIT {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String MESSAGE_DESTINATION = "/app/conversations/%s/messages";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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
    private SimpUserRegistry simpUserRegistry;

    private TestUser sender;
    private UUID conversationId;
    private WebSocketStompClient client;
    private StompSession session;
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    private record TestUser(UUID id, String accessToken) {
    }

    @BeforeEach
    void connectForMessageProbes() throws Exception {
        sender = registerUser("Payload Sender");
        TestUser peer = registerUser("Payload Peer");
        MvcResult direct = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + sender.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", peer.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        conversationId = UUID.fromString(objectMapper.readTree(
                direct.getResponse().getContentAsString()).get("id").asText());

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new RawByteMessageConverter(), new MappingJackson2MessageConverter())));
        client.setTaskScheduler(new ConcurrentTaskScheduler(stompTaskScheduler));
        client.setDefaultHeartbeat(new long[]{0, 0});
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + sender.accessToken());
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
    void brokerAnswersClientHeartbeatWithNonZeroServerHeartbeat() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        java.net.http.WebSocket socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(java.net.URI.create("ws://localhost:" + port + "/ws"),
                        new java.net.http.WebSocket.Listener() {
                            @Override
                            public java.util.concurrent.CompletionStage<?> onText(
                                    java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                                frames.add(data.toString());
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            socket.request(1);
            socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nAuthorization:Bearer "
                    + sender.accessToken() + "\n\n\u0000", true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String connected = frames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(connected).startsWith("CONNECTED");
            String heartbeat = java.util.Arrays.stream(connected.split("\n"))
                    .filter(line -> line.startsWith("heart-beat:")).findFirst().orElse("");
            assertThat(heartbeat).isNotEmpty().isNotEqualTo("heart-beat:0,0");
        } finally {
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    void malformedAndOversizedBodiesReturnSafeValidationErrorsOnSameSession() throws Exception {
        BlockingQueue<WsErrorResponse> errors = subscribeErrors();
        String destination = MESSAGE_DESTINATION.formatted(conversationId);

        expectValidationError(destination,
                "{\"clientMessageId\":\"not-a-uuid\",\"type\":\"TEXT\",\"content\":\"hello\"}",
                MimeTypeUtils.APPLICATION_JSON, errors);
        expectValidationError(destination, "null", MimeTypeUtils.APPLICATION_JSON, errors);
        expectValidationError(destination, "[]", MimeTypeUtils.APPLICATION_JSON, errors);
        expectValidationError(destination, "", MimeTypeUtils.APPLICATION_JSON, errors);
        expectValidationError(destination,
                "{\"clientMessageId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"TEXT\",\"content\":\"hello\"}",
                MimeTypeUtils.TEXT_PLAIN, errors);

        String oversizedClientMessageId = UUID.randomUUID().toString();
        String oversizedBody = "{\"clientMessageId\":\"" + oversizedClientMessageId
                + "\",\"type\":\"TEXT\",\"content\":\"" + "x".repeat(10_001) + "\"}";
        expectValidationError(destination, oversizedBody, MimeTypeUtils.APPLICATION_JSON, errors,
                oversizedClientMessageId);
        assertThat(session.isConnected()).isTrue();
        // The same session must still receive a safe error after an oversized payload.
        expectValidationError(destination, "null", MimeTypeUtils.APPLICATION_JSON, errors);

        assertThat(session.isConnected()).isTrue();
    }

    private BlockingQueue<WsErrorResponse> subscribeErrors() {
        BlockingQueue<WsErrorResponse> errors = new LinkedBlockingQueue<>();
        String subscriptionId = "errors-" + UUID.randomUUID();
        StompHeaders headers = new StompHeaders();
        headers.setId(subscriptionId);
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
        TestWaits.until("STOMP error queue subscription of " + sender.id() + " to be registered",
                () -> isSubscribed(sender.id(), subscriptionId, "/user/queue/errors"));
        return errors;
    }

    private boolean isSubscribed(UUID userId, String subscriptionId, String destination) {
        String brokerDestination = destination.startsWith("/user/")
                ? destination.substring("/user".length())
                : destination;
        var registeredUser = simpUserRegistry.getUser(userId.toString());
        return registeredUser != null && registeredUser.getSessions().stream()
                .flatMap(userSession -> userSession.getSubscriptions().stream())
                .filter(subscription -> subscriptionId.equals(subscription.getId()))
                .map(subscription -> subscription.getDestination())
                .anyMatch(actual -> actual != null
                        && (actual.equals(destination) || actual.equals(brokerDestination)
                        || actual.startsWith(brokerDestination + "-user")));
    }

    private void expectValidationError(String destination, String body, MimeType contentType,
                                       BlockingQueue<WsErrorResponse> errors) throws Exception {
        expectValidationError(destination, body, contentType, errors, null);
    }

    private void expectValidationError(String destination, String body, MimeType contentType,
                                       BlockingQueue<WsErrorResponse> errors,
                                       String expectedClientMessageId) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(contentType);
        session.send(headers, body.getBytes(StandardCharsets.UTF_8));

        WsErrorResponse error = errors.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        if (expectedClientMessageId == null) {
            assertThat(error.clientMessageId()).isNull();
        } else {
            assertThat(error.clientMessageId().toString()).isEqualTo(expectedClientMessageId);
        }
        assertThat(objectMapper.writeValueAsString(error))
                .doesNotContain("message", "stackTrace", "exception");
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(WsPayloadSecurityIT.class);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private static final class RawByteMessageConverter implements MessageConverter {
        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return payload instanceof byte[] ? MessageBuilder.createMessage(payload, headers) : null;
        }

        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            return targetClass == byte[].class && message.getPayload() instanceof byte[]
                    ? message.getPayload()
                    : null;
        }
    }
}
