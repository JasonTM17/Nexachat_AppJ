package com.nexachat.ws;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-message realtime metadata obeys the same admission boundary as the
 * message itself — same env-JDBC + {@link WebSocketStompClient} pattern as
 * {@code MessageFlowIT}.
 *
 * <p>Pin, reaction and recall frames name a message without carrying its
 * content, but they still disclose its existence: a member admitted after the
 * message was created must not receive them, while an admitted member keeps
 * receiving all three.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionRealtimeMetadataIT {

    private static final String WS_URL_PATTERN = "ws://localhost:%d/ws";
    private static final long WS_TIMEOUT_SECONDS = 10;
    private static final long NEGATIVE_POLL_MS = 900;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HikariDataSource dataSource;
    @Autowired
    private SimpUserRegistry simpUserRegistry;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser owner;
    private TestUser watcher;
    private TestUser lateJoiner;

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, AdmissionRealtimeMetadataIT.class);
        String tag = "ARM" + System.nanoTime();
        owner = registerUser(tag + " Owner");
        watcher = registerUser(tag + " Watcher");
        lateJoiner = registerUser("Zz" + tag + " Late");
    }

    @AfterAll
    void closeStompClients() {
        stompClients.forEach(WebSocketStompClient::stop);
        stompTaskScheduler.shutdownNow();
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(AdmissionRealtimeMetadataIT.class);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                UUID.fromString(body.get("user").get("id").asText()),
                phone, displayName, body.get("accessToken").asText());
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID createGroup(TestUser creator, String title, List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", title, "memberIds", ids))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID sendRest(UUID conversationId, TestUser sender, String content) throws Exception {
        MvcResult result = postJson(
                "/api/v1/conversations/" + conversationId + "/messages", sender.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    @Test
    void lateJoinerReceivesNoInteractionFramesForPreAdmissionMessage() throws Exception {
        UUID conversationId = createGroup(owner, "Metadata scope", List.of(watcher.id()));
        UUID message = sendRest(conversationId, owner, "before late joiner");
        postJson("/api/v1/conversations/" + conversationId + "/members", owner.accessToken(),
                Map.of("userIds", List.of(lateJoiner.id().toString())))
                .andExpect(status().isNoContent());

        StompSession watcherSession = connect(watcher);
        StompSession lateSession = connect(lateJoiner);
        try {
            BlockingQueue<String> watcherTopic = subscribeToQueue(
                    watcherSession, watcher, "/topic/conversations." + conversationId);
            BlockingQueue<String> lateTopic = subscribeToQueue(
                    lateSession, lateJoiner, "/topic/conversations." + conversationId);

            postJson("/api/v1/conversations/" + conversationId + "/pins", owner.accessToken(),
                    Map.of("messageId", message.toString()))
                    .andExpect(status().isNoContent());
            assertThat(nextFrameFor(watcherTopic, "PIN_UPDATED", message, WS_TIMEOUT_SECONDS * 1000))
                    .isNotNull();
            assertNoFrameFor(lateTopic, "PIN_UPDATED", message);

            postJson("/api/v1/messages/" + message + "/reactions", owner.accessToken(),
                    Map.of("emoji", "👍"))
                    .andExpect(status().isNoContent());
            assertThat(nextFrameFor(watcherTopic, "REACTION_UPDATED", message, WS_TIMEOUT_SECONDS * 1000))
                    .isNotNull();
            assertNoFrameFor(lateTopic, "REACTION_UPDATED", message);

            mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, message)
                            .header("Authorization", "Bearer " + owner.accessToken()))
                    .andExpect(status().isNoContent());
            // Recalling a pinned message also emits PIN_UPDATED{pinned:false}.
            assertThat(nextFrameFor(watcherTopic, "PIN_UPDATED", message, WS_TIMEOUT_SECONDS * 1000))
                    .isNotNull();
            assertThat(nextFrameFor(watcherTopic, "MESSAGE_DELETED", message, WS_TIMEOUT_SECONDS * 1000))
                    .isNotNull();
            assertNoFrameFor(lateTopic, "PIN_UPDATED", message);
            assertNoFrameFor(lateTopic, "MESSAGE_DELETED", message);
        } finally {
            watcherSession.disconnect();
            lateSession.disconnect();
        }
    }

    /**
     * Drains the queue until a frame of {@code eventType} naming
     * {@code messageId} arrives or {@code windowMs} elapses; {@code null} on
     * timeout. Interaction and recall frames name their message by
     * {@code payload.messageId}.
     */
    private JsonNode nextFrameFor(BlockingQueue<String> queue, String eventType,
                                  UUID messageId, long windowMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            String raw = queue.poll(200, TimeUnit.MILLISECONDS);
            if (raw == null) {
                continue;
            }
            JsonNode frame = objectMapper.readTree(raw);
            if (eventType.equals(frame.path("eventType").asText())
                    && messageId.toString().equals(
                            frame.path("payload").path("messageId").asText(""))) {
                return frame;
            }
        }
        return null;
    }

    private void assertNoFrameFor(BlockingQueue<String> queue, String eventType,
                                  UUID messageId) throws Exception {
        assertThat(nextFrameFor(queue, eventType, messageId, NEGATIVE_POLL_MS))
                .as("late joiner must not receive %s for a pre-admission message", eventType)
                .isNull();
    }

    private WebSocketStompClient newStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(new ConcurrentTaskScheduler(stompTaskScheduler));
        client.setDefaultHeartbeat(new long[]{0, 0});
        stompClients.add(client);
        return client;
    }

    private StompSession connect(TestUser user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + user.accessToken());
        return newStompClient()
                .connectAsync(String.format(WS_URL_PATTERN, port),
                        (WebSocketHttpHeaders) null, connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private BlockingQueue<String> subscribeToQueue(
            StompSession session, TestUser user, String destination) throws Exception {
        String subscriptionId = "subscribe-" + UUID.randomUUID();
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setId(subscriptionId);
        headers.setDestination(destination);
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        await().atMost(Duration.ofSeconds(WS_TIMEOUT_SECONDS)).untilAsserted(() ->
                assertThat(isSubscribed(user.id(), subscriptionId, destination)).isTrue());
        return frames;
    }

    private boolean isSubscribed(UUID userId, String subscriptionId, String destination) {
        String brokerDestination = destination.startsWith("/user/")
                ? destination.substring("/user".length())
                : destination;
        var registeredUser = simpUserRegistry.getUser(userId.toString());
        return registeredUser != null && registeredUser.getSessions().stream()
                .flatMap(session -> session.getSubscriptions().stream())
                .filter(subscription -> subscriptionId.equals(subscription.getId()))
                .map(subscription -> subscription.getDestination())
                .anyMatch(actual -> actual != null
                        && (actual.equals(destination) || actual.equals(brokerDestination)
                        || actual.startsWith(brokerDestination + "-user")));
    }
}
