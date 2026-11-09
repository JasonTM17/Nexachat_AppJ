package com.nexachat.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the per-member conversation pin and the "seen"
 * privacy switch against a real PostgreSQL and a real STOMP connection:
 * authZ matrix, the five-pin ceiling, inbox/delta ordering flags, reciprocal
 * read-receipt suppression and the STOMP negative membership cases.
 *
 * <p>{@code PER_CLASS}: the users are registered once because the auth
 * register bucket (10 / 5 min / IP) is shared across the suite. Each test
 * starts from a clean conversation graph for these users.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationPinSeenFlowIT {

    private static final String WS_URL_PATTERN = "ws://localhost:%d/ws";
    private static final long WS_TIMEOUT_SECONDS = 10;
    private static final long NEGATIVE_POLL_MS = 2_000;

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
    private SimpUserRegistry simpUserRegistry;
    @Autowired
    private ConversationPinService pinService;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private TestUser dave;
    private TestUser erin;
    private TestUser frank;

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final List<StompSession> stompSessions = new ArrayList<>();
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void registerUsers() throws Exception {
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN"
                        + " (SELECT id FROM users WHERE phone LIKE '+84916%')");
        jdbcTemplate.update("DELETE FROM users WHERE phone LIKE '+84916%'");
        String tag = "ITPS" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
        erin = registerUser(tag + " Erin");
        frank = registerUser(tag + " Frank");
    }

    @BeforeEach
    void cleanState() {
        deleteConversationsForTestUsers();
        String placeholders = String.join(",", java.util.Collections.nCopies(6, "?"));
        jdbcTemplate.update("UPDATE users SET show_seen = TRUE WHERE id IN (" + placeholders + ")",
                alice.id(), bob.id(), carol.id(), dave.id(), erin.id(), frank.id());
    }

    @AfterEach
    void closeStomp() {
        stompSessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        stompSessions.clear();
        stompClients.forEach(WebSocketStompClient::stop);
        stompClients.clear();
    }

    private void deleteConversationsForTestUsers() {
        if (alice == null) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(6, "?"));
        jdbcTemplate.update("DELETE FROM conversations WHERE created_by IN (" + placeholders + ")",
                alice.id(), bob.id(), carol.id(), dave.id(), erin.id(), frank.id());
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+84916" + String.format("%06d", random.nextInt(1_000_000));
    }

    private TestUser registerUser(String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", randomPhone(), "password", "secret123",
                                        "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    // ------------------------------------------------------------------ HTTP

    private ResultActions getJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions patchJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(patch(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(put(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(delete(path).header("Authorization", "Bearer " + accessToken));
    }

    private UUID openDirectConversation(TestUser opener, TestUser partner) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", opener.accessToken(),
                Map.of("userId", partner.id().toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createGroup(TestUser creator, String title, List<TestUser> members) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", title,
                        "memberIds", members.stream().map(user -> user.id().toString()).toList()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private long sendMessage(UUID conversationId, TestUser sender, String content) throws Exception {
        MvcResult result = postJson(
                "/api/v1/conversations/" + conversationId + "/messages", sender.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("seq").asLong();
    }

    private List<JsonNode> inbox(String accessToken) throws Exception {
        MvcResult result = getJson("/api/v1/conversations", accessToken)
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> entries = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString()).forEach(entries::add);
        return entries;
    }

    private JsonNode inboxEntry(List<JsonNode> entries, UUID conversationId) {
        return entries.stream()
                .filter(entry -> conversationId.toString().equals(entry.get("id").asText()))
                .findFirst()
                .orElse(null);
    }

    private List<UUID> inboxIds(String accessToken) throws Exception {
        return inbox(accessToken).stream()
                .map(entry -> UUID.fromString(entry.get("id").asText()))
                .toList();
    }

    private void setShowSeen(TestUser user, boolean showSeen) throws Exception {
        patchJson("/api/v1/users/me/settings", user.accessToken(), Map.of("showSeen", showSeen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showSeen").value(showSeen));
    }

    // ------------------------------------------------------------------ STOMP

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
        StompSession session = newStompClient()
                .connectAsync(String.format(WS_URL_PATTERN, port),
                        (WebSocketHttpHeaders) null, connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        stompSessions.add(session);
        return session;
    }

    private BlockingQueue<String> subscribeToTopic(StompSession session, TestUser user,
                                                   UUID conversationId) throws Exception {
        String destination = "/topic/conversations." + conversationId;
        String subscriptionId = "subscribe-" + UUID.randomUUID();
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setId(subscriptionId);
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

    private BlockingQueue<String> subscribeWithoutAwait(StompSession session, UUID conversationId) {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/topic/conversations." + conversationId);
        headers.setId("unauthorized-" + UUID.randomUUID());
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
        return frames;
    }

    private boolean isSubscribed(UUID userId, String subscriptionId, String destination) {
        var registeredUser = simpUserRegistry.getUser(userId.toString());
        return registeredUser != null && registeredUser.getSessions().stream()
                .flatMap(session -> session.getSubscriptions().stream())
                .filter(subscription -> subscriptionId.equals(subscription.getId()))
                .map(subscription -> subscription.getDestination())
                .anyMatch(actual -> destination.equals(actual));
    }

    private JsonNode awaitEvent(BlockingQueue<String> frames, String eventType,
                                long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return null;
            }
            String raw = frames.poll(remaining, TimeUnit.NANOSECONDS);
            if (raw == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (eventType.equals(node.get("eventType").asText())) {
                return node;
            }
        }
    }

    private void assertNoEvent(BlockingQueue<String> frames, String eventType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NEGATIVE_POLL_MS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            String raw = frames.poll(remaining, TimeUnit.NANOSECONDS);
            if (raw == null) {
                return;
            }
            JsonNode node = objectMapper.readTree(raw);
            assertThat(node.get("eventType").asText())
                    .as("unexpected %s frame", eventType)
                    .isNotEqualTo(eventType);
        }
    }

    private boolean receivesFrameContaining(BlockingQueue<String> frames, String value,
                                            long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            String frame = frames.poll(remaining, TimeUnit.NANOSECONDS);
            if (frame == null) {
                return false;
            }
            if (frame.contains(value)) {
                return true;
            }
        }
    }

    // ================================================================== pins

    @Test
    void pinAuthzCapAndIdempotency() throws Exception {
        UUID first = openDirectConversation(alice, bob);
        UUID second = openDirectConversation(alice, carol);
        UUID third = openDirectConversation(alice, dave);
        UUID fourth = openDirectConversation(alice, erin);
        UUID fifth = openDirectConversation(alice, frank);
        UUID sixth = createGroup(alice, "Sixth", List.of(bob));

        for (UUID conversationId : List.of(first, second, third, fourth, fifth)) {
            putJson("/api/v1/conversations/" + conversationId + "/pin", alice.accessToken(), Map.of())
                    .andExpect(status().isNoContent());
        }

        // The sixth NEW pin is over the per-user ceiling.
        putJson("/api/v1/conversations/" + sixth + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_PIN_LIMIT"));
        assertThat(inboxEntry(inbox(alice.accessToken()), sixth).get("pinned").asBoolean()).isFalse();

        // Re-pinning an already pinned conversation is idempotent even at the cap.
        putJson("/api/v1/conversations/" + first + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        // Unpinning frees exactly one slot.
        deleteJson("/api/v1/conversations/" + first + "/pin", alice.accessToken())
                .andExpect(status().isNoContent());
        deleteJson("/api/v1/conversations/" + first + "/pin", alice.accessToken())
                .andExpect(status().isNoContent());
        putJson("/api/v1/conversations/" + sixth + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        // Per-member authZ: a non-member cannot pin or unpin.
        UUID aliceBob = openDirectConversation(alice, bob);
        putJson("/api/v1/conversations/" + aliceBob + "/pin", carol.accessToken(), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        deleteJson("/api/v1/conversations/" + aliceBob + "/pin", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // A different user has an independent cap and sees only its own pin state.
        putJson("/api/v1/conversations/" + aliceBob + "/pin", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        assertThat(inboxEntry(inbox(alice.accessToken()), aliceBob).get("pinned").asBoolean()).isFalse();
        assertThat(inboxEntry(inbox(bob.accessToken()), aliceBob).get("pinned").asBoolean()).isTrue();

        // Missing conversation and missing authentication.
        putJson("/api/v1/conversations/" + UUID.randomUUID() + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/conversations/" + aliceBob + "/pin")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/conversations/" + aliceBob + "/pin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentPinsCannotExceedThePerUserCeiling() throws Exception {
        List<UUID> conversations = new ArrayList<>();
        conversations.add(openDirectConversation(alice, bob));
        conversations.add(openDirectConversation(alice, carol));
        conversations.add(openDirectConversation(alice, dave));
        conversations.add(openDirectConversation(alice, erin));
        conversations.add(openDirectConversation(alice, frank));
        conversations.add(createGroup(alice, "Concurrent cap", List.of(bob)));

        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(conversations.size());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        try {
            for (UUID conversationId : conversations) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        pinService.pin(alice.id(), conversationId);
                        return true;
                    } catch (com.nexachat.common.error.ApiException ex) {
                        assertThat(ex.getCode())
                                .isEqualTo(com.nexachat.common.error.ApiErrorCode.CONVERSATION_PIN_LIMIT);
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (java.util.concurrent.Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM conversation_members WHERE user_id = ? AND pinned = TRUE",
                    Integer.class, alice.id())).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inboxOrdersPinnedFirstThenByActivityAndCarriesCallerFlags() throws Exception {
        UUID older = openDirectConversation(alice, bob);
        sendMessage(older, alice, "older");
        UUID newer = openDirectConversation(alice, carol);
        sendMessage(newer, alice, "newer");

        assertThat(inboxIds(alice.accessToken())).containsExactly(newer, older);

        putJson("/api/v1/conversations/" + older + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        JsonNode pinned = inboxEntry(inbox(alice.accessToken()), older);
        assertThat(pinned.get("pinned").asBoolean()).isTrue();
        assertThat(pinned.get("pinnedAt").asText()).isNotBlank();
        JsonNode unpinned = inboxEntry(inbox(alice.accessToken()), newer);
        assertThat(unpinned.get("pinned").asBoolean()).isFalse();
        assertThat(unpinned.has("pinnedAt")).isFalse();
        assertThat(inboxIds(alice.accessToken())).containsExactly(older, newer);

        // Both pinned: activity order applies inside the pinned group.
        putJson("/api/v1/conversations/" + newer + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        assertThat(inboxIds(alice.accessToken())).containsExactly(newer, older);

        // One pinned one not: the pinned row wins even with a lower activity seq.
        deleteJson("/api/v1/conversations/" + newer + "/pin", alice.accessToken())
                .andExpect(status().isNoContent());
        assertThat(inboxIds(alice.accessToken())).containsExactly(older, newer);

        // Paged inbox keeps the same order and cursor semantics across the
        // pinned/unpinned boundary.
        MvcResult firstPage = getJson("/api/v1/conversations?limit=1", alice.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstItems = objectMapper.readTree(firstPage.getResponse().getContentAsString());
        assertThat(firstItems).hasSize(1);
        assertThat(firstItems.get(0).get("id").asText()).isEqualTo(older.toString());
        String cursor = firstPage.getResponse().getHeader("X-Next-Cursor");
        assertThat(cursor).isNotBlank();

        MvcResult secondPage = getJson("/api/v1/conversations?limit=1&cursor=" + cursor,
                alice.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondItems = objectMapper.readTree(secondPage.getResponse().getContentAsString());
        assertThat(secondItems).hasSize(1);
        assertThat(secondItems.get(0).get("id").asText()).isEqualTo(newer.toString());

        // Pin state is the caller's only: the partner sees its own false flag.
        JsonNode partnerEntry = inboxEntry(inbox(bob.accessToken()), older);
        assertThat(partnerEntry.get("pinned").asBoolean()).isFalse();
        assertThat(partnerEntry.has("pinnedAt")).isFalse();
    }

    @Test
    void deltaSyncCarriesTheCallerPinFlags() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        sendMessage(conversationId, alice, "delta pin");

        postJson("/api/v1/sync", alice.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].pinned").value(false))
                .andExpect(jsonPath("$.deltas[0].pinnedAt").doesNotExist());

        putJson("/api/v1/conversations/" + conversationId + "/pin", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        MvcResult pinnedSync = postJson("/api/v1/sync", alice.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].pinned").value(true))
                .andReturn();
        JsonNode pinnedDelta = objectMapper.readTree(
                pinnedSync.getResponse().getContentAsString()).get("deltas").get(0);
        assertThat(pinnedDelta.get("pinnedAt").asText()).isNotBlank();

        // The other member learns nothing about alice's pin.
        postJson("/api/v1/sync", bob.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].pinned").value(false))
                .andExpect(jsonPath("$.deltas[0].pinnedAt").doesNotExist());

        deleteJson("/api/v1/conversations/" + conversationId + "/pin", alice.accessToken())
                .andExpect(status().isNoContent());
        postJson("/api/v1/sync", alice.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].pinned").value(false))
                .andExpect(jsonPath("$.deltas[0].pinnedAt").doesNotExist());
    }

    // ============================================================== seen privacy

    @Test
    void seenPrivacyIsReciprocalAndNeverChangesOwnUnreadCount() throws Exception {
        UUID groupId = createGroup(alice, "Seen privacy", List.of(bob, carol));
        long seedSeq = sendMessage(groupId, alice, "seed");

        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        BlockingQueue<String> aliceTopic = subscribeToTopic(aliceSession, alice, groupId);
        BlockingQueue<String> bobTopic = subscribeToTopic(bobSession, bob, groupId);
        BlockingQueue<String> carolTopic = subscribeToTopic(carolSession, carol, groupId);

        getJson("/api/v1/users/me/settings", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showSeen").value(true));
        setShowSeen(alice, false);
        getJson("/api/v1/users/me/settings", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showSeen").value(false));

        // Bob shares seen: carol receives his receipt, alice (hidden) does not.
        bobSession.send("/app/conversations/" + groupId + "/read", Map.of("upToSeq", seedSeq));
        JsonNode bobReceipt = awaitEvent(carolTopic, "MESSAGE_READ", WS_TIMEOUT_SECONDS);
        assertThat(bobReceipt).isNotNull();
        assertThat(bobReceipt.get("payload").get("userId").asText()).isEqualTo(bob.id().toString());
        assertNoEvent(aliceTopic, "MESSAGE_READ");

        // Alice's own receipt is not broadcast at all, but her watermark and
        // unread count behave exactly as before.
        long secondSeq = sendMessage(groupId, bob, "second");
        aliceTopic.clear();
        bobTopic.clear();
        carolTopic.clear();
        aliceSession.send("/app/conversations/" + groupId + "/read", Map.of("upToSeq", secondSeq));
        assertNoEvent(bobTopic, "MESSAGE_READ");
        assertNoEvent(carolTopic, "MESSAGE_READ");

        Long aliceWatermark = jdbcTemplate.queryForObject(
                "SELECT last_read_seq FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Long.class, groupId, alice.id());
        assertThat(aliceWatermark).isEqualTo(secondSeq);
        JsonNode aliceGroup = inboxEntry(inbox(alice.accessToken()), groupId);
        assertThat(aliceGroup.get("unreadCount").asLong()).isZero();
        assertThat(aliceGroup.get("lastReadSeq").asLong()).isEqualTo(secondSeq);

        // Turning seen back on restores the receipt to the other members.
        setShowSeen(alice, true);
        long thirdSeq = sendMessage(groupId, bob, "third");
        aliceTopic.clear();
        bobTopic.clear();
        carolTopic.clear();
        aliceSession.send("/app/conversations/" + groupId + "/read", Map.of("upToSeq", thirdSeq));
        JsonNode aliceReceipt = awaitEvent(bobTopic, "MESSAGE_READ", WS_TIMEOUT_SECONDS);
        assertThat(aliceReceipt).isNotNull();
        assertThat(aliceReceipt.get("payload").get("userId").asText()).isEqualTo(alice.id().toString());
        assertThat(aliceReceipt.get("payload").get("upToSeq").asLong()).isEqualTo(thirdSeq);
    }

    // ========================================================== STOMP negatives

    @Test
    void nonMemberCannotSendTypingReadOrSubscribe() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        long latestSeq = sendMessage(conversationId, alice, "seed");

        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        BlockingQueue<String> bobTopic = subscribeToTopic(bobSession, bob, conversationId);
        BlockingQueue<String> carolTopic = subscribeWithoutAwait(carolSession, conversationId);

        // Carol is not a member: SUBSCRIBE, typing and read are silently dropped.
        carolSession.send("/app/conversations/" + conversationId + "/typing", Map.of());
        carolSession.send("/app/conversations/" + conversationId + "/read",
                Map.of("upToSeq", latestSeq));

        long markerSeq = sendMessage(conversationId, alice, "member marker");
        assertThat(markerSeq).isPositive();
        JsonNode created = awaitEvent(bobTopic, "MESSAGE_CREATED", WS_TIMEOUT_SECONDS);
        assertThat(created).isNotNull();
        assertThat(created.get("payload").get("content").asText()).isEqualTo("member marker");
        assertThat(carolTopic.poll(500, TimeUnit.MILLISECONDS)).isNull();

        bobTopic.clear();
        assertNoEvent(bobTopic, "USER_TYPING");
        assertNoEvent(bobTopic, "MESSAGE_READ");

        // The non-member's read never created a watermark row.
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, carol.id());
        assertThat(rows).isZero();
    }

    @Test
    void removedMemberLosesTheConversationSubscription() throws Exception {
        UUID groupId = createGroup(alice, "Revoked subscription", List.of(bob, carol));
        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        BlockingQueue<String> bobTopic = subscribeToTopic(bobSession, bob, groupId);
        BlockingQueue<String> carolTopic = subscribeToTopic(carolSession, carol, groupId);

        sendMessage(groupId, alice, "before removal marker");
        assertThat(receivesFrameContaining(carolTopic, "before removal marker", WS_TIMEOUT_SECONDS))
                .isTrue();

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/members/{userId}",
                        groupId, carol.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        sendMessage(groupId, alice, "after removal marker");
        assertThat(receivesFrameContaining(bobTopic, "after removal marker", WS_TIMEOUT_SECONDS))
                .isTrue();
        assertThat(receivesFrameContaining(carolTopic, "after removal marker", 2)).isFalse();
    }
}
