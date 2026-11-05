package com.nexachat.presence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PresenceFlowIT {

    private static final String WS_URL_PATTERN = "ws://localhost:%d/ws";
    private static final long WS_TIMEOUT_SECONDS = 10;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.presence.disconnect-coalesce-ms", () -> 5_000L);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @LocalServerPort private int port;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SimpUserRegistry simpUserRegistry;

    private record TestUser(UUID id, String accessToken) { }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private UUID conversationId;
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "ITP" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        MvcResult direct = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", bob.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        conversationId = UUID.fromString(objectMapper.readTree(
                direct.getResponse().getContentAsString()).get("id").asText());
    }

    @AfterAll
    void closeSessions() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        clients.forEach(WebSocketStompClient::stop);
        stompTaskScheduler.shutdownNow();
    }

    @Test
    void realStompSessionsFanOutPresenceOnlyToDirectPeersAndHonorPrivacy() throws Exception {
        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        BlockingQueue<String> bobPresence = subscribe(bobSession, bob, "/user/queue/presence");
        BlockingQueue<String> carolPresence = subscribe(carolSession, carol, "/user/queue/presence");

        StompSession aliceFirst = connect(alice);
        JsonNode online = readFrame(bobPresence);
        assertThat(online.get("eventType").asText()).isEqualTo("USER_ONLINE");
        assertThat(online.get("conversationId").isNull()).isTrue();
        assertThat(online.get("payload").get("userId").asText()).isEqualTo(alice.id().toString());
        assertThat(online.get("payload").get("online").asBoolean()).isTrue();
        assertThat(carolPresence.poll(300, TimeUnit.MILLISECONDS)).isNull();

        StompSession aliceSecond = connect(alice);
        assertThat(bobPresence.poll(300, TimeUnit.MILLISECONDS)).isNull();
        aliceFirst.disconnect();
        assertThat(peerPresence(bob, conversationId).get("online").asBoolean()).isTrue();
        aliceSecond.disconnect();

        JsonNode offline = readFrame(bobPresence);
        assertThat(offline.get("eventType").asText()).isEqualTo("USER_OFFLINE");
        assertThat(offline.get("payload").get("userId").asText()).isEqualTo(alice.id().toString());
        assertThat(offline.get("payload").get("online").asBoolean()).isFalse();
        assertThat(offline.get("payload").get("lastSeenAt").isTextual()).isTrue();
        Instant eventLastSeen = Instant.parse(offline.get("payload").get("lastSeenAt").asText());
        LocalDateTime storedLastSeenUtc = jdbcTemplate.queryForObject(
                "SELECT last_seen_at AT TIME ZONE 'UTC' FROM users WHERE id = ?",
                LocalDateTime.class, alice.id());
        assertThat(storedLastSeenUtc).isNotNull();
        assertThat(eventLastSeen).isEqualTo(storedLastSeenUtc.toInstant(ZoneOffset.UTC));
        assertThat(eventLastSeen.getNano() % 1_000).isZero();

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showPresence\":false}"))
                .andExpect(status().isOk());
        JsonNode settings = objectMapper.readTree(mockMvc.perform(get("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(settings.get("showPresence").asBoolean()).isFalse();

        StompSession hiddenAlice = connect(alice);
        assertThat(bobPresence.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(carolPresence.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(peerPresence(bob, conversationId)).isNull();
        hiddenAlice.disconnect();
        bobSession.disconnect();
        carolSession.disconnect();
    }

    @Test
    void concurrentLastDisconnectAndQuickReconnectCoalesceThePresenceFlap() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showPresence\":true}"))
                .andExpect(status().isOk());

        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        BlockingQueue<String> bobPresence = subscribe(bobSession, bob, "/user/queue/presence");
        BlockingQueue<String> carolPresence = subscribe(carolSession, carol, "/user/queue/presence");
        StompSession aliceFirst = connect(alice);
        JsonNode online = readFrame(bobPresence);
        assertThat(online.get("eventType").asText()).isEqualTo("USER_ONLINE");
        assertThat(online.get("payload").get("userId").asText()).isEqualTo(alice.id().toString());

        StompSession aliceSecond = connect(alice);
        assertThat(bobPresence.poll(200, TimeUnit.MILLISECONDS)).isNull();

        ExecutorService disconnectors = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        long disconnectStartedAt = System.nanoTime();
        try {
            Future<?> firstDisconnect = disconnectors.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent disconnect gate timed out");
                }
                aliceFirst.disconnect();
                return null;
            });
            Future<?> secondDisconnect = disconnectors.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent disconnect gate timed out");
                }
                aliceSecond.disconnect();
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            disconnectStartedAt = System.nanoTime();
            start.countDown();
            firstDisconnect.get(5, TimeUnit.SECONDS);
            secondDisconnect.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(peerPresence(bob, conversationId).get("online").asBoolean()).isFalse());
            long disconnectedObservedAt = System.nanoTime();

            assertThat(System.nanoTime() - disconnectStartedAt)
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));
            StompSession aliceReconnected = connect(alice);
            long elapsedSinceDisconnectMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - disconnectedObservedAt);
            assertThat(System.nanoTime() - disconnectStartedAt)
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));

            long remainingWindowMs = Math.max(1, 5_100 - elapsedSinceDisconnectMs);
            assertThat(bobPresence.poll(remainingWindowMs, TimeUnit.MILLISECONDS)).isNull();
            assertThat(peerPresence(bob, conversationId).get("online").asBoolean()).isTrue();
            assertThat(carolPresence.poll(200, TimeUnit.MILLISECONDS)).isNull();

            aliceReconnected.disconnect();
            JsonNode offline = readFrame(bobPresence);
            assertThat(offline.get("eventType").asText()).isEqualTo("USER_OFFLINE");
            assertThat(offline.get("payload").get("userId").asText()).isEqualTo(alice.id().toString());
            assertThat(bobPresence.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(carolPresence.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            start.countDown();
            disconnectors.shutdownNow();
            if (aliceFirst.isConnected()) {
                aliceFirst.disconnect();
            }
            if (aliceSecond.isConnected()) {
                aliceSecond.disconnect();
            }
            bobSession.disconnect();
            carolSession.disconnect();
        }
    }

    private JsonNode peerPresence(TestUser user, UUID id) throws Exception {
        JsonNode entries = objectMapper.readTree(mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode entry : entries) {
            if (id.toString().equals(entry.get("id").asText())) {
                return entry.get("peerPresence");
            }
        }
        return null;
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8490" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private StompSession connect(TestUser user) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(new ConcurrentTaskScheduler(stompTaskScheduler));
        client.setDefaultHeartbeat(new long[]{0, 0});
        clients.add(client);
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + user.accessToken());
        StompSession session = client.connectAsync(String.format(WS_URL_PATTERN, port),
                        (WebSocketHttpHeaders) null, headers, new StompSessionHandlerAdapter() { })
                .get(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<String> subscribe(StompSession session, TestUser user, String destination)
            throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        String subscriptionId = "presence-" + UUID.randomUUID();
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
        await().atMost(Duration.ofSeconds(WS_TIMEOUT_SECONDS)).untilAsserted(() -> {
            assertThat(isSubscribed(user.id(), subscriptionId, destination)).isTrue();
        });
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

    private JsonNode readFrame(BlockingQueue<String> frames) throws Exception {
        String frame = frames.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(frame).isNotNull();
        return objectMapper.readTree(frame);
    }
}
