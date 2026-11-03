package com.nexachat.message;

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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the P5 direct-chat slice against a real PostgreSQL —
 * same env-JDBC pattern as {@code ContactConversationIT} — plus a REAL STOMP
 * handshake against the randomly-ported embedded server
 * ({@code ws://localhost:{port}/ws} via {@link WebSocketStompClient}).
 *
 * <p>Covers the whole contract surface: REST send + idempotent dedup, cursor
 * pagination over 35 messages, inbox unread counts before/after the read
 * watermark, member-check enforcement, and the WS path CONNECT → SUBSCRIBE →
 * SEND → {@code MESSAGE_CREATED} on the topic and {@code /user/queue/inbox},
 * USER_TYPING, MESSAGE_READ, plus negative CONNECT (bad token) and negative
 * SUBSCRIBE/SEND (non-member) authZ.
 *
 * <p>{@code PER_CLASS}: the three users are registered once — the auth
 * register bucket (10 / 5 min / IP) is shared across the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageFlowIT {

    private static final String WS_URL_PATTERN = "ws://localhost:%d/ws";
    private static final long WS_TIMEOUT_SECONDS = 10;
    private static final long NEGATIVE_POLL_MS = 900;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // never joins the conversation — the non-member probe

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();

    @BeforeAll
    void registerUsers() throws Exception {
        String like = "phone LIKE '+8490%' AND phone NOT LIKE '+849000%'";
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
        String tag = "ITM" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
    }

    @AfterAll
    void closeStompClients() {
        stompClients.forEach(WebSocketStompClient::stop);
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8490" + (1 + random.nextInt(9)) + String.format("%06d", random.nextInt(1_000_000));
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = randomPhone();
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

    private ResultActions getJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID openDirectConversation() throws Exception {
        return openDirectConversation(alice, bob);
    }

    private UUID openDirectConversation(TestUser opener, TestUser partner) throws Exception {
        // 201 on first create, 200 when the direct_key already maps to a row —
        // the WS tests share one alice/bob pair so both are valid.
        MvcResult result = postJson("/api/v1/conversations/direct", opener.accessToken(),
                Map.of("userId", partner.id().toString()))
                .andExpect(status().is2xxSuccessful())
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

    private JsonNode inboxEntryFor(UUID conversationId, TestUser user) throws Exception {
        MvcResult result = getJson("/api/v1/conversations", user.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode entry : objectMapper.readTree(result.getResponse().getContentAsString())) {
            if (entry.get("id").asText().equals(conversationId.toString())) {
                return entry;
            }
        }
        return null;
    }

    // ============================================================ REST flow

    @Test
    void restSendHistoryInboxAndReadWatermark() throws Exception {
        // Dedicated alice↔carol conversation: exact-count assertions below must
        // not see messages the WS tests send into the shared alice↔bob room.
        UUID conversationId = openDirectConversation(alice, carol);

        // --- 35 messages in, page them 30+5 ---
        List<UUID> sentIds = new ArrayList<>();
        List<UUID> clientMessageIds = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            UUID clientMessageId = UUID.randomUUID();
            clientMessageIds.add(clientMessageId);
            MvcResult result = postJson(
                    "/api/v1/conversations/" + conversationId + "/messages",
                    alice.accessToken(),
                    Map.of("clientMessageId", clientMessageId.toString(),
                            "type", "TEXT", "content", "msg-" + i))
                    .andExpect(status().isCreated())
                    .andReturn();
            sentIds.add(UUID.fromString(objectMapper
                    .readTree(result.getResponse().getContentAsString()).get("id").asText()));
        }
        assertThat(new HashSet<>(sentIds)).hasSize(35);

        // --- idempotent replay: same clientMessageId → 200 + same id, still 35 rows ---
        MvcResult replay = postJson(
                "/api/v1/conversations/" + conversationId + "/messages",
                alice.accessToken(),
                Map.of("clientMessageId", clientMessageIds.get(0).toString(),
                        "type", "TEXT", "content", "msg-1"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())
                .get("id").asText()).isEqualTo(sentIds.get(0).toString());
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
        assertThat(stored).isEqualTo(35);

        // --- page 1: newest 30, DESC, hasMore ---
        MvcResult page1 = getJson(
                "/api/v1/conversations/" + conversationId + "/messages?limit=30",
                carol.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn();
        JsonNode page1Body = objectMapper.readTree(page1.getResponse().getContentAsString());
        List<Long> seqs = new ArrayList<>();
        page1Body.get("items").forEach(item -> seqs.add(item.get("seq").asLong()));
        assertThat(seqs).isSortedAccordingTo((a, b) -> Long.compare(b, a)); // strictly DESC
        long nextBeforeSeq = page1Body.get("nextBeforeSeq").asLong();
        assertThat(nextBeforeSeq).isEqualTo(seqs.get(seqs.size() - 1));

        // --- page 2: the remaining 5, strictly older ---
        MvcResult page2 = getJson(
                "/api/v1/conversations/" + conversationId + "/messages?beforeSeq=" + nextBeforeSeq + "&limit=30",
                carol.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();
        JsonNode page2Body = objectMapper.readTree(page2.getResponse().getContentAsString());
        List<Long> page2Seqs = new ArrayList<>();
        Set<String> allIds = new HashSet<>();
        page1Body.get("items").forEach(item -> allIds.add(item.get("id").asText()));
        page2Body.get("items").forEach(item -> {
            page2Seqs.add(item.get("seq").asLong());
            allIds.add(item.get("id").asText());
        });
        assertThat(page2Seqs.get(0)).isLessThan(seqs.get(seqs.size() - 1));
        assertThat(allIds).hasSize(35); // no overlap, no gaps in the logical feed

        // every row carries the canonical MessageResponse shape
        JsonNode first = page1Body.get("items").get(0);
        assertThat(first.get("senderId").asText()).isEqualTo(alice.id().toString());
        assertThat(first.get("senderDisplayName").asText()).isEqualTo(alice.displayName());
        assertThat(first.get("type").asText()).isEqualTo("TEXT");
        assertThat(first.has("clientMessageId")).isTrue();
        assertThat(first.has("conversationId")).isTrue();
        assertThat(first.has("createdAt")).isTrue();

        // --- inbox BEFORE read: carol sees 35 unread, alice 0 (sender watermark) ---
        long maxSeq = seqs.get(0);
        JsonNode carolInbox = inboxEntryFor(conversationId, carol);
        assertThat(carolInbox).isNotNull();
        assertThat(carolInbox.get("unreadCount").asLong()).isEqualTo(35);
        assertThat(carolInbox.get("lastReadSeq").asLong()).isEqualTo(0);
        assertThat(carolInbox.get("lastMessage").get("seq").asLong()).isEqualTo(maxSeq);
        assertThat(carolInbox.get("lastMessage").get("type").asText()).isEqualTo("TEXT");
        assertThat(carolInbox.get("lastMessage").get("contentSnippet").asText()).isEqualTo("msg-35");
        assertThat(carolInbox.get("members")).hasSize(2);
        JsonNode aliceInbox = inboxEntryFor(conversationId, alice);
        assertThat(aliceInbox.get("unreadCount").asLong()).isEqualTo(0);

        // --- read watermark: POST /read then inbox flips ---
        postJson("/api/v1/conversations/" + conversationId + "/read", carol.accessToken(),
                Map.of("upToSeq", maxSeq))
                .andExpect(status().isNoContent());
        JsonNode carolInboxAfter = inboxEntryFor(conversationId, carol);
        assertThat(carolInboxAfter.get("unreadCount").asLong()).isEqualTo(0);
        assertThat(carolInboxAfter.get("lastReadSeq").asLong()).isEqualTo(maxSeq);
        Long storedWatermark = jdbcTemplate.queryForObject(
                "SELECT last_read_seq FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Long.class, conversationId, carol.id());
        assertThat(storedWatermark).isEqualTo(maxSeq);
    }

    @Test
    void nonMemberCannotReadWriteOrWatermark() throws Exception {
        UUID conversationId = openDirectConversation();
        sendRest(conversationId, alice, "private");

        getJson("/api/v1/conversations/" + conversationId + "/messages", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/conversations/" + conversationId + "/read", carol.accessToken(),
                Map.of("upToSeq", 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/conversations/" + conversationId + "/messages", carol.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT", "content", "spy"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // The conversation is invisible in carol's inbox too.
        assertThat(inboxEntryFor(conversationId, carol)).isNull();

        // Member on a nonexistent conversation → 404, not 403.
        getJson("/api/v1/conversations/" + UUID.randomUUID() + "/messages", alice.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
    }

    // ============================================================== WS flow

    private WebSocketStompClient newStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
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

    private BlockingQueue<String> subscribeToQueue(StompSession session, String destination) {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
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

    @Test
    void wsConnectSubscribeSendBroadcastTypingAndRead() throws Exception {
        UUID conversationId = openDirectConversation();

        StompSession aliceSession = connect(alice);
        StompSession bobSession = connect(bob);
        BlockingQueue<String> aliceTopic = subscribeToQueue(aliceSession, "/topic/conversations." + conversationId);
        BlockingQueue<String> bobTopic = subscribeToQueue(bobSession, "/topic/conversations." + conversationId);
        BlockingQueue<String> bobInbox = subscribeToQueue(bobSession, "/user/queue/inbox");
        // SUBSCRIBE frames are fire-and-forget — let them register before sends.
        Thread.sleep(400);

        // --- SEND via STOMP → both subscribers + sender get MESSAGE_CREATED ---
        UUID clientMessageId = UUID.randomUUID();
        aliceSession.send("/app/conversations/" + conversationId + "/messages",
                Map.of("clientMessageId", clientMessageId.toString(),
                        "type", "TEXT", "content", "ws hello"));

        JsonNode bobFrame = objectMapper.readTree(
                bobTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(bobFrame).isNotNull();
        assertThat(bobFrame.get("eventType").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(bobFrame.get("conversationId").asText()).isEqualTo(conversationId.toString());
        JsonNode payload = bobFrame.get("payload");
        assertThat(payload.get("clientMessageId").asText()).isEqualTo(clientMessageId.toString());
        assertThat(payload.get("content").asText()).isEqualTo("ws hello");
        assertThat(payload.get("senderId").asText()).isEqualTo(alice.id().toString());
        long wsSeq = payload.get("seq").asLong();
        assertThat(wsSeq).isPositive();

        // sender sees its own broadcast (dedup by clientMessageId client-side)
        JsonNode aliceFrame = objectMapper.readTree(
                aliceTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(aliceFrame).isNotNull();
        assertThat(aliceFrame.get("payload").get("clientMessageId").asText())
                .isEqualTo(clientMessageId.toString());

        // inbox push lands on /user/queue/inbox even without a topic subscription
        JsonNode inboxFrame = objectMapper.readTree(
                bobInbox.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(inboxFrame).isNotNull();
        assertThat(inboxFrame.get("eventType").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(inboxFrame.get("payload").get("clientMessageId").asText())
                .isEqualTo(clientMessageId.toString());

        // --- typing: broadcast to the topic with the sender's userId ---
        bobSession.send("/app/conversations/" + conversationId + "/typing", Map.of());
        JsonNode typingFrame = objectMapper.readTree(
                aliceTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(typingFrame).isNotNull();
        assertThat(typingFrame.get("eventType").asText()).isEqualTo("USER_TYPING");
        assertThat(typingFrame.get("payload").get("userId").asText()).isEqualTo(bob.id().toString());

        // --- read via STOMP → MESSAGE_READ broadcast + watermark stored ---
        bobSession.send("/app/conversations/" + conversationId + "/read",
                Map.of("upToSeq", wsSeq));
        JsonNode readFrame = objectMapper.readTree(
                aliceTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(readFrame).isNotNull();
        assertThat(readFrame.get("eventType").asText()).isEqualTo("MESSAGE_READ");
        assertThat(readFrame.get("payload").get("userId").asText()).isEqualTo(bob.id().toString());
        assertThat(readFrame.get("payload").get("upToSeq").asLong()).isEqualTo(wsSeq);
        Long watermark = jdbcTemplate.queryForObject(
                "SELECT last_read_seq FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Long.class, conversationId, bob.id());
        assertThat(watermark).isEqualTo(wsSeq);

        aliceSession.disconnect();
        bobSession.disconnect();
    }

    @Test
    void wsRestSendAlsoBroadcastsToTopic() throws Exception {
        UUID conversationId = openDirectConversation();
        StompSession bobSession = connect(bob);
        BlockingQueue<String> bobTopic = subscribeToQueue(bobSession, "/topic/conversations." + conversationId);
        Thread.sleep(400); // let the SUBSCRIBE register before the send

        // REST send (the fallback path) hits the same service → same broadcast.
        sendRest(conversationId, alice, "rest via ws fan-out");

        JsonNode frame = objectMapper.readTree(
                bobTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(frame).isNotNull();
        assertThat(frame.get("eventType").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(frame.get("payload").get("content").asText()).isEqualTo("rest via ws fan-out");
        bobSession.disconnect();
    }

    @Test
    void wsRejectsBadConnectAndDropsNonMemberTraffic() throws Exception {
        UUID conversationId = openDirectConversation();

        // --- bad token: CONNECT is dropped; no CONNECTED ever arrives ---
        StompHeaders badHeaders = new StompHeaders();
        badHeaders.add("Authorization", "Bearer not-a-real-token");
        var rejected = newStompClient().connectAsync(
                String.format(WS_URL_PATTERN, port),
                (WebSocketHttpHeaders) null, badHeaders,
                new StompSessionHandlerAdapter() {
                });
        assertThatThrownBy(() -> rejected.get(4, TimeUnit.SECONDS))
                .isInstanceOfAny(java.util.concurrent.TimeoutException.class,
                        java.util.concurrent.ExecutionException.class);

        // --- valid CONNECT but non-member: SUBSCRIBE + SEND silently dropped ---
        StompSession carolSession = connect(carol);
        BlockingQueue<String> carolTopic = subscribeToQueue(
                carolSession, "/topic/conversations." + conversationId);
        StompSession aliceSession = connect(alice);
        BlockingQueue<String> aliceTopic = subscribeToQueue(
                aliceSession, "/topic/conversations." + conversationId);
        Thread.sleep(400); // let the SUBSCRIBE register before the sends

        carolSession.send("/app/conversations/" + conversationId + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "spy"));
        // A member's parallel send proves the pipe works while carol's is dead.
        sendRest(conversationId, alice, "real");

        assertThat(aliceTopic.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull(); // "real" lands
        assertThat(carolTopic.poll(NEGATIVE_POLL_MS, TimeUnit.MILLISECONDS)).isNull(); // nothing for carol
        Integer spyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND content = 'spy'",
                Integer.class, conversationId);
        assertThat(spyCount).isZero();

        aliceSession.disconnect();
        carolSession.disconnect();
    }
}
