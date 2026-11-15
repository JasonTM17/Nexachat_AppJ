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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Realtime admission boundary on the live STOMP transport — same env-JDBC +
 * {@link WebSocketStompClient} pattern as {@code MessageFlowIT}.
 *
 * <p>The broadcast {@code MessageResponse} is hydrated once for every
 * member: a rejoined author's {@code MESSAGE_UPDATED} must still carry the
 * quote preview for members who never lost admission, while the author's own
 * REST response stays scoped to their admission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdmissionRealtimeScopeIT {

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
    private HikariDataSource dataSource;
    @Autowired
    private SimpUserRegistry simpUserRegistry;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser owner;
    private TestUser rejoined;
    private TestUser watcher;

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final ScheduledExecutorService stompTaskScheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, AdmissionRealtimeScopeIT.class);
        String tag = "ARS" + System.nanoTime();
        owner = registerUser(tag + " Owner");
        rejoined = registerUser(tag + " Rejoined");
        watcher = registerUser("Zz" + tag + " Watcher");
    }

    @AfterAll
    void closeStompClients() {
        stompClients.forEach(WebSocketStompClient::stop);
        stompTaskScheduler.shutdownNow();
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(AdmissionRealtimeScopeIT.class);
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

    private JsonNode sendRest(UUID conversationId, TestUser sender, String content,
                              UUID replyToId) throws Exception {
        Map<String, String> body = replyToId == null
                ? Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content)
                : Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", content, "replyToId", replyToId.toString());
        MvcResult result = postJson(
                "/api/v1/conversations/" + conversationId + "/messages", sender.accessToken(), body)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * A rejoined author's edit broadcast must still carry the quote preview
     * for members who never lost admission. The shared frame is hydrated once
     * — scoping it to the author would strip the quote for everyone.
     */
    @Test
    void rejoinedAuthorEditKeepsQuoteForAdmittedMembers() throws Exception {
        UUID conversationId = createGroup(owner, "Quote scope", List.of(rejoined.id(), watcher.id()));
        UUID quoted = UUID.fromString(
                sendRest(conversationId, owner, "quoted target", null).get("id").asText());
        UUID reply = UUID.fromString(
                sendRest(conversationId, rejoined, "reply before leaving", quoted).get("id").asText());

        // Rejoined leaves and is re-added — admission restarts after the quote.
        postJson("/api/v1/conversations/" + conversationId + "/leave", rejoined.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        postJson("/api/v1/conversations/" + conversationId + "/members", owner.accessToken(),
                Map.of("userIds", List.of(rejoined.id().toString())))
                .andExpect(status().isNoContent());

        StompSession watcherSession = connect(watcher);
        try {
            BlockingQueue<String> watcherTopic = subscribeToQueue(
                    watcherSession, watcher, "/topic/conversations." + conversationId);
            JsonNode edited = objectMapper.readTree(mockMvc.perform(patch(
                                    "/api/v1/conversations/{c}/messages/{m}", conversationId, reply)
                            .header("Authorization", "Bearer " + rejoined.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("content", "reply edited"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

            // The REST edit response is scoped to the author: the quote predates
            // their rejoin, so only replyToId survives — history agrees.
            assertThat(edited.get("replyToId").asText()).isEqualTo(quoted.toString());
            assertThat(edited.get("replyTo")).isNull();

            // The broadcast frame must still carry the full preview; the
            // watcher was admitted when the quoted message was sent.
            JsonNode frame = nextFrameFor(watcherTopic, "MESSAGE_UPDATED", reply, WS_TIMEOUT_SECONDS * 1000);
            assertThat(frame).isNotNull();
            assertThat(frame.path("payload").path("replyTo").path("id").asText())
                    .isEqualTo(quoted.toString());
            assertThat(frame.path("payload").path("content").asText()).isEqualTo("reply edited");
        } finally {
            watcherSession.disconnect();
        }
    }

    /**
     * Drains the queue until a frame of {@code eventType} naming
     * {@code messageId} arrives or {@code windowMs} elapses; {@code null} on
     * timeout. Message frames identify themselves by {@code payload.id},
     * interaction and recall frames by {@code payload.messageId}.
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
            if (!eventType.equals(frame.path("eventType").asText())) {
                continue;
            }
            JsonNode payload = frame.path("payload");
            String named = payload.path("id").isTextual()
                    ? payload.path("id").asText() : payload.path("messageId").asText("");
            if (messageId.toString().equals(named)) {
                return frame;
            }
        }
        return null;
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
