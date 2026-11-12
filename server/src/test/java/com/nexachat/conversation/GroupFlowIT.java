package com.nexachat.conversation;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the P6 group-chat slice against a real PostgreSQL —
 * same env-JDBC pattern as {@code MessageFlowIT} — plus a real STOMP inbox
 * assertion ({@code ws://localhost:{port}/ws} via {@link WebSocketStompClient}).
 *
 * <p>Covers: create (roles ADMIN/MEMBER, one tx), non-member read → 403,
 * non-admin member ops → 403 NOT_ADMIN, idempotent add, SYSTEM rows in
 * history, member removal (kicked user loses REST access + inbox entry),
 * self-leave via both POST /leave and DELETE self, the sole-admin guard, the
 * last-member empty-group rule, PATCH rename, and the
 * {@code CONVERSATION_UPDATED} inbox push reaching a newly added member.
 *
 * <p>Test users use the phone range assigned to this suite by
 * {@code ItPhoneRanges}, so cleanup cannot remove another suite's fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupFlowIT {

    private static final String WS_URL_PATTERN = "ws://localhost:%d/ws";
    private static final long WS_TIMEOUT_SECONDS = 10;

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
    private SimpleBrokerMessageHandler simpleBrokerMessageHandler;
    @Autowired
    @Qualifier("clientInboundChannel")
    private ExecutorSubscribableChannel clientInboundChannel;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice; // group creator → ADMIN
    private TestUser bob;
    private TestUser carol;

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, GroupFlowIT.class);
        String tag = "ITG" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    @AfterAll
    void closeStompClients() {
        stompClients.forEach(WebSocketStompClient::stop);
        stompTaskScheduler.shutdownNow();
    }

    private static String randomPhone() {
        return ItPhoneRanges.nextPhone(GroupFlowIT.class);
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

    private List<JsonNode> systemMessages(UUID conversationId, TestUser reader) throws Exception {
        MvcResult result = getJson(
                "/api/v1/conversations/" + conversationId + "/messages?limit=100",
                reader.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> system = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items").forEach(item -> {
                    if ("SYSTEM".equals(item.get("type").asText())) {
                        system.add(item);
                    }
                });
        return system;
    }

    // ================================================================== flow

    @Test
    void groupLifecycleRestFlow() throws Exception {
        // --- create: alice ADMIN + bob MEMBER in one tx; SYSTEM line seeds history ---
        UUID groupId = createGroup(alice, "Study Group", List.of(bob.id()));
        String role = jdbcTemplate.queryForObject(
                "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                String.class, groupId, alice.id());
        assertThat(role).isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                String.class, groupId, bob.id())).isEqualTo("MEMBER");
        assertThat(systemMessages(groupId, alice))
                .anySatisfy(item -> assertThat(item.get("content").asText()).contains("created"));

        // --- non-member is locked out of history AND sends ---
        getJson("/api/v1/conversations/" + groupId + "/messages", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // --- a MEMBER cannot add (admin-only) ---
        postJson("/api/v1/conversations/" + groupId + "/members", bob.accessToken(),
                Map.of("userIds", List.of(carol.id().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        // --- admin adds carol (duplicate ids collapse) → 204, access granted ---
        postJson("/api/v1/conversations/" + groupId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(carol.id().toString(), carol.id().toString())))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId)).isEqualTo(3);
        getJson("/api/v1/conversations/" + groupId + "/messages", carol.accessToken())
                .andExpect(status().isOk());
        assertThat(inboxEntryFor(groupId, carol)).isNotNull();
        assertThat(systemMessages(groupId, carol))
                .anySatisfy(item -> assertThat(item.get("content").asText()).contains("added"));

        // --- idempotent re-add: still 3 members, no extra SYSTEM rows ---
        postJson("/api/v1/conversations/" + groupId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(carol.id().toString())))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId)).isEqualTo(3);
        long systemCountAfterReAdd = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND type = 'SYSTEM'",
                Long.class, groupId);
        assertThat(systemCountAfterReAdd).isEqualTo(2L); // created + added

        // --- rename: 200 payload carries the new title + SYSTEM "renamed" ---
        MvcResult patched = mockMvc.perform(patch("/api/v1/conversations/" + groupId)
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "New Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Name"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andReturn();
        assertThat(objectMapper.readTree(patched.getResponse().getContentAsString())
                .get("members").findValuesAsText("role")).contains("ADMIN");
        assertThat(systemMessages(groupId, alice))
                .anySatisfy(item -> assertThat(item.get("content").asText()).contains("renamed"));

        // --- kick carol: 204, her inbox drops the room, history → 403 ---
        mockMvc.perform(delete("/api/v1/conversations/" + groupId + "/members/" + carol.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());
        getJson("/api/v1/conversations/" + groupId + "/messages", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        assertThat(inboxEntryFor(groupId, carol)).isNull();
        assertThat(systemMessages(groupId, alice))
                .anySatisfy(item -> assertThat(item.get("content").asText()).contains("removed"));

        // --- self-leave via DELETE (plan: ADMIN or the user themselves) ---
        postJson("/api/v1/conversations/" + groupId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(carol.id().toString())))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/conversations/" + groupId + "/members/" + carol.id())
                        .header("Authorization", "Bearer " + carol.accessToken()))
                .andExpect(status().isNoContent());
        getJson("/api/v1/conversations/" + groupId + "/messages", carol.accessToken())
                .andExpect(status().isForbidden());

        // --- sole ADMIN cannot leave while members remain ---
        postJson("/api/v1/conversations/" + groupId + "/leave", alice.accessToken(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // --- bob leaves via POST /leave → access gone, inbox excludes ---
        postJson("/api/v1/conversations/" + groupId + "/leave", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        getJson("/api/v1/conversations/" + groupId + "/messages", bob.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        assertThat(inboxEntryFor(groupId, bob)).isNull();
        assertThat(systemMessages(groupId, alice))
                .anySatisfy(item -> assertThat(item.get("content").asText()).contains("left"));

        // --- last member (sole admin, alone) may leave: empty group persists ---
        postJson("/api/v1/conversations/" + groupId + "/leave", alice.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        getJson("/api/v1/conversations/" + groupId + "/messages", alice.accessToken())
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE id = ?", Integer.class, groupId))
                .isEqualTo(1); // row stays
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId)).isZero();
    }

    @Test
    void groupCreateAndMemberValidation() throws Exception {
        // blank / overlong / empty-list creates → 400
        postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "  ", "memberIds", List.of(bob.id().toString())))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "x".repeat(81), "memberIds", List.of(bob.id().toString())))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "Solo", "memberIds", List.of()))
                .andExpect(status().isBadRequest());
        // creator alone → 400
        postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "Solo", "memberIds", List.of(alice.id().toString())))
                .andExpect(status().isBadRequest());
        // unknown member → 404 USER_NOT_FOUND
        postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "Ghost", "memberIds", List.of(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        UUID groupId = createGroup(alice, "Checks", List.of(bob.id()));

        // unknown user to add → 404
        postJson("/api/v1/conversations/" + groupId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
        // removing a non-member → 403 NOT_MEMBER
        mockMvc.perform(delete(
                        "/api/v1/conversations/" + groupId + "/members/" + carol.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        // non-member leave → 403
        postJson("/api/v1/conversations/" + groupId + "/leave", carol.accessToken(), Map.of())
                .andExpect(status().isForbidden());
        // non-member PATCH → 403 NOT_MEMBER (membership checked before admin)
        mockMvc.perform(patch("/api/v1/conversations/" + groupId)
                        .header("Authorization", "Bearer " + carol.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Hijack"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        // member (not admin) PATCH → 403 NOT_ADMIN
        mockMvc.perform(patch("/api/v1/conversations/" + groupId)
                        .header("Authorization", "Bearer " + bob.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Hijack"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        // PATCH on a DIRECT conversation → 400 (group ops are GROUP-only)
        MvcResult direct = postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", bob.id().toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID directId = UUID.fromString(objectMapper
                .readTree(direct.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(patch("/api/v1/conversations/" + directId)
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Hijack"))))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/conversations/" + directId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(carol.id().toString())))
                .andExpect(status().isBadRequest());
    }

    // ==================================================================== WS

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
        return subscribeToQueue(session, user, destination, "subscribe-" + UUID.randomUUID());
    }

    private BlockingQueue<String> subscribeToQueue(
            StompSession session, TestUser user, String destination, String subscriptionId) throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setId(subscriptionId);
        var subscription = session.subscribe(headers, new StompFrameHandler() {
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
                .flatMap(userSession -> userSession.getSubscriptions().stream())
                .filter(subscription -> subscriptionId.equals(subscription.getId()))
                .map(subscription -> subscription.getDestination())
                .anyMatch(actual -> actual != null
                        && (actual.equals(destination) || actual.equals(brokerDestination)
                        || actual.startsWith(brokerDestination + "-user")));
    }

    private boolean isBrokerSubscribed(String sessionId, String subscriptionId, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return simpleBrokerMessageHandler.getSubscriptionRegistry().findSubscriptions(message)
                .getOrDefault(sessionId, List.of())
                .contains(subscriptionId);
    }

    private static final class SubscriptionDispatchGate implements ChannelInterceptor {
        private final String destination;
        private final CountDownLatch authorized = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile String sessionId;

        private SubscriptionDispatchGate(String destination) {
            this.destination = destination;
        }

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            if (accessor.getCommand() == StompCommand.SUBSCRIBE
                    && destination.equals(accessor.getDestination())) {
                sessionId = accessor.getSessionId();
                authorized.countDown();
                try {
                    if (!released.await(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the subscription test gate");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return message;
        }

        private boolean awaitAuthorization() throws InterruptedException {
            return authorized.await(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private String sessionId() {
            return sessionId;
        }

        private void release() {
            released.countDown();
        }
    }

    private void sendTextMessage(UUID conversationId, TestUser sender, String content) throws Exception {
        postJson("/api/v1/conversations/" + conversationId + "/messages", sender.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content))
                .andExpect(status().isCreated());
    }

    private boolean receivesFrameContaining(
            BlockingQueue<String> frames, String value, long timeoutSeconds) throws InterruptedException {
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

    @Test
    void nonAdminMemberCannotUploadGroupAvatarOverHttp() throws Exception {
        UUID groupId = createGroup(alice, "Avatar authorization", List.of(bob.id()));
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/conversations/" + groupId + "/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
    }

    @Test
    void inboxPushReachesAddedMember() throws Exception {
        UUID groupId = createGroup(alice, "WS Room", List.of(bob.id()));

        // carol is NOT a member yet — her user queue subscription is still
        // allowed (it is her own queue) so the push lands when she is added.
        StompSession carolSession = connect(carol);
        BlockingQueue<String> carolInbox = subscribeToQueue(carolSession, carol, "/user/queue/inbox");

        postJson("/api/v1/conversations/" + groupId + "/members", alice.accessToken(),
                Map.of("userIds", List.of(carol.id().toString())))
                .andExpect(status().isNoContent());

        // The add produces MESSAGE_CREATED (SYSTEM "added") then
        // CONVERSATION_UPDATED — skip ahead to the update frame.
        JsonNode frame = null;
        for (int i = 0; i < 4; i++) {
            String raw = carolInbox.poll(WS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (raw == null) {
                break;
            }
            JsonNode candidate = objectMapper.readTree(raw);
            if ("CONVERSATION_UPDATED".equals(candidate.get("eventType").asText())) {
                frame = candidate;
                break;
            }
        }
        assertThat(frame).isNotNull();
        assertThat(frame.get("conversationId").asText()).isEqualTo(groupId.toString());
        assertThat(frame.get("payload").get("members").findValuesAsText("userId"))
                .contains(carol.id().toString());
        carolSession.disconnect();
    }

    @Test
    void adminRemovalStopsLaterMessagesOnExistingConversationSubscription() throws Exception {
        UUID groupId = createGroup(alice, "Removal subscription", List.of(bob.id(), carol.id()));
        StompSession carolSession = connect(carol);
        try {
            BlockingQueue<String> topic = subscribeToQueue(
                    carolSession, carol, "/topic/conversations." + groupId);
            sendTextMessage(groupId, alice, "before admin removal marker");
            assertThat(receivesFrameContaining(topic, "before admin removal marker", WS_TIMEOUT_SECONDS))
                    .isTrue();

            mockMvc.perform(delete("/api/v1/conversations/{conversationId}/members/{userId}",
                            groupId, carol.id())
                            .header("Authorization", "Bearer " + alice.accessToken()))
                    .andExpect(status().isNoContent());
            sendTextMessage(groupId, alice, "after admin removal marker");

            assertThat(receivesFrameContaining(topic, "after admin removal marker", 2)).isFalse();
        } finally {
            carolSession.disconnect();
        }
    }

    @Test
    void leavingMemberStopsLaterMessagesOnExistingConversationSubscription() throws Exception {
        UUID groupId = createGroup(alice, "Leave subscription", List.of(bob.id(), carol.id()));
        StompSession carolSession = connect(carol);
        try {
            BlockingQueue<String> topic = subscribeToQueue(
                    carolSession, carol, "/topic/conversations." + groupId);
            sendTextMessage(groupId, alice, "before member leave marker");
            assertThat(receivesFrameContaining(topic, "before member leave marker", WS_TIMEOUT_SECONDS))
                    .isTrue();

            postJson("/api/v1/conversations/" + groupId + "/leave", carol.accessToken(), Map.of())
                    .andExpect(status().isNoContent());
            sendTextMessage(groupId, alice, "after member leave marker");

            assertThat(receivesFrameContaining(topic, "after member leave marker", 2)).isFalse();
        } finally {
            carolSession.disconnect();
        }
    }

    @Test
    void queuedSubscriptionAfterRemovalCannotReceiveLaterMessages() throws Exception {
        UUID groupId = createGroup(alice, "Queued subscription", List.of(bob.id(), carol.id()));
        String destination = "/topic/conversations." + groupId;
        StompSession bobSession = connect(bob);
        StompSession carolSession = connect(carol);
        String bobSubscriptionId = "current-member-" + UUID.randomUUID();
        String carolSubscriptionId = "queued-subscribe-" + UUID.randomUUID();
        BlockingQueue<String> bobTopic = subscribeToQueue(bobSession, bob, destination, bobSubscriptionId);
        BlockingQueue<String> carolTopic = new LinkedBlockingQueue<>();
        SubscriptionDispatchGate gate = new SubscriptionDispatchGate(destination);
        clientInboundChannel.addInterceptor(gate);
        try {
            StompHeaders headers = new StompHeaders();
            headers.setDestination(destination);
            headers.setId(carolSubscriptionId);
            carolSession.subscribe(headers, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    carolTopic.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                }
            });
            assertThat(gate.awaitAuthorization()).isTrue();

            mockMvc.perform(delete("/api/v1/conversations/{conversationId}/members/{userId}",
                            groupId, carol.id())
                            .header("Authorization", "Bearer " + alice.accessToken()))
                    .andExpect(status().isNoContent());

            gate.release();
            await().atMost(Duration.ofSeconds(WS_TIMEOUT_SECONDS)).untilAsserted(() ->
                    assertThat(isBrokerSubscribed(
                            gate.sessionId(), carolSubscriptionId, destination))
                            .as("broker registry contains queued subscription")
                            .isTrue());

            String marker = "queued subscribe after removal marker";
            sendTextMessage(groupId, alice, marker);
            assertThat(receivesFrameContaining(bobTopic, marker, WS_TIMEOUT_SECONDS)).isTrue();
            assertThat(receivesFrameContaining(carolTopic, marker, 2)).isFalse();
        } finally {
            gate.release();
            clientInboundChannel.removeInterceptor(gate);
            carolSession.disconnect();
            bobSession.disconnect();
        }
    }
}
