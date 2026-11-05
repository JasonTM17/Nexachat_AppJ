package com.nexachat.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.ws.dto.WsErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nexachat.calls.ring-timeout-ms=5000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallFlowIT {

    private static final long TIMEOUT_SECONDS = 10;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
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

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private WebSocketStompClient client;
    private ScheduledExecutorService taskScheduler;
    private final List<StompSession> sessions = new ArrayList<>();

    private record TestUser(UUID id, String accessToken) {
    }

    private record Connection(StompSession session, BlockingQueue<JsonNode> calls,
                             BlockingQueue<WsErrorResponse> errors) {
    }

    @BeforeAll
    void registerTestUsers() throws Exception {
        alice = registerUser("Call Alice");
        bob = registerUser("Call Bob");
        carol = registerUser("Call Carol");
    }

    @BeforeEach
    void setUp() {
        taskScheduler = Executors.newSingleThreadScheduledExecutor();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(List.of(
                new RawByteMessageConverter(), new MappingJackson2MessageConverter())));
        client.setTaskScheduler(new ConcurrentTaskScheduler(taskScheduler));
        client.setDefaultHeartbeat(new long[]{0, 0});
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : sessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        sessions.clear();
        if (client != null) {
            client.stop();
        }
        if (taskScheduler != null) {
            taskScheduler.shutdownNow();
        }
    }

    @Test
    void inviteAcceptRelaysMediaEndsAndPersistsHistoryAndSystemMessage() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        long beforeSeq = latestSeq(getMessages(conversationId, alice, null));
        Connection caller = connect(alice);
        Connection callee = connect(bob);
        UUID callId = UUID.randomUUID();

        send(caller, conversationId, new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        assertThat(awaitEvent(callee, "CALL_INVITE").path("fromUserId").asText()).isEqualTo(alice.id().toString());

        send(callee, conversationId, new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        assertThat(awaitEvent(caller, "CALL_ACCEPTED").path("callId").asText()).isEqualTo(callId.toString());
        assertThat(awaitEvent(callee, "CALL_ACCEPTED").path("callId").asText()).isEqualTo(callId.toString());

        CallSignal.Sdp sdp = new CallSignal.Sdp("offer", "v=0\\r\\no=nexachat 1 1 IN IP4 127.0.0.1");
        send(caller, conversationId, new CallSignal(CallSignal.Type.SDP, callId, sdp, null));
        assertThat(awaitEvent(callee, "CALL_SDP").path("payload").path("sdp").path("description").asText())
                .isEqualTo(sdp.description());
        CallSignal.Candidate candidate = new CallSignal.Candidate("audio", 0, "candidate:1 1 UDP 1 127.0.0.1 9 typ host");
        send(callee, conversationId, new CallSignal(CallSignal.Type.ICE, callId, null, candidate));
        assertThat(awaitEvent(caller, "CALL_ICE").path("payload").path("candidate").path("candidate").asText())
                .isEqualTo(candidate.candidate());

        send(caller, conversationId, new CallSignal(CallSignal.Type.END, callId, null, null));
        assertThat(awaitEvent(caller, "CALL_ENDED").path("payload").path("reason").asText()).isEqualTo("COMPLETED");
        assertThat(awaitEvent(callee, "CALL_ENDED").path("payload").path("reason").asText()).isEqualTo("COMPLETED");

        JsonNode history = getJson("/api/v1/conversations/" + conversationId + "/calls", alice);
        JsonNode completed = callHistoryItem(history, callId);
        assertThat(completed.get("state").asText()).isEqualTo("ENDED");
        assertThat(completed.get("endReason").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("durationSeconds").asLong()).isGreaterThanOrEqualTo(0);

        List<JsonNode> callLines = callLinesAfter(conversationId, alice, beforeSeq);
        assertThat(callLines).hasSize(1);
        assertThat(callLines.get(0).get("content").asText()).isEqualTo("CALL|COMPLETED|"
                + completed.get("durationSeconds").asLong());
        assertThat(getJson("/api/v1/calls/ice-config", alice).get("iceServers")).isNotEmpty();
    }

    @Test
    void busyInviteAndRejectedCallProduceTheExpectedTerminalFrames() throws Exception {
        UUID aliceBob = openDirect(alice, bob);
        UUID carolBob = openDirect(carol, bob);
        Connection aliceSocket = connect(alice);
        Connection bobSocket = connect(bob);
        Connection carolSocket = connect(carol);
        UUID activeCallId = UUID.randomUUID();
        send(aliceSocket, aliceBob, new CallSignal(CallSignal.Type.INVITE, activeCallId, null, null));
        awaitEvent(bobSocket, "CALL_INVITE");
        send(bobSocket, aliceBob, new CallSignal(CallSignal.Type.ACCEPT, activeCallId, null, null));
        awaitEvent(aliceSocket, "CALL_ACCEPTED");
        awaitEvent(bobSocket, "CALL_ACCEPTED");

        UUID busyCallId = UUID.randomUUID();
        send(carolSocket, carolBob, new CallSignal(CallSignal.Type.INVITE, busyCallId, null, null));
        assertThat(awaitEvent(carolSocket, "CALL_BUSY").path("payload").path("reason").asText()).isEqualTo("BUSY");
        assertThat(bobSocket.calls().poll(250, TimeUnit.MILLISECONDS)).isNull();

        send(aliceSocket, aliceBob, new CallSignal(CallSignal.Type.END, activeCallId, null, null));
        awaitEvent(aliceSocket, "CALL_ENDED");
        awaitEvent(bobSocket, "CALL_ENDED");

        UUID rejectedCallId = UUID.randomUUID();
        send(aliceSocket, aliceBob, new CallSignal(CallSignal.Type.INVITE, rejectedCallId, null, null));
        awaitEvent(bobSocket, "CALL_INVITE");
        send(bobSocket, aliceBob, new CallSignal(CallSignal.Type.REJECT, rejectedCallId, null, null));
        assertThat(awaitEvent(aliceSocket, "CALL_REJECTED").path("payload").path("reason").asText())
                .isEqualTo("REJECTED");
        assertThat(awaitEvent(bobSocket, "CALL_REJECTED").path("payload").path("reason").asText())
                .isEqualTo("REJECTED");

        JsonNode busyHistory = getJson("/api/v1/conversations/" + carolBob + "/calls", carol);
        assertThat(callHistoryItem(busyHistory, busyCallId).get("endReason").asText()).isEqualTo("BUSY");
        JsonNode directHistory = getJson("/api/v1/conversations/" + aliceBob + "/calls", alice);
        assertThat(callHistoryItem(directHistory, rejectedCallId).get("endReason").asText()).isEqualTo("REJECTED");
    }

    @Test
    void callerCanCancelARingingCall() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        long beforeSeq = latestSeq(getMessages(conversationId, alice, null));
        Connection caller = connect(alice);
        Connection callee = connect(bob);
        UUID callId = UUID.randomUUID();

        send(caller, conversationId, new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        awaitEvent(callee, "CALL_INVITE");
        send(caller, conversationId, new CallSignal(CallSignal.Type.CANCEL, callId, null, null));
        assertThat(awaitEvent(caller, "CALL_CANCELLED").path("payload").path("reason").asText())
                .isEqualTo("CANCELLED");
        assertThat(awaitEvent(callee, "CALL_CANCELLED").path("payload").path("reason").asText())
                .isEqualTo("CANCELLED");

        JsonNode history = getJson("/api/v1/conversations/" + conversationId + "/calls", alice);
        assertThat(callHistoryItem(history, callId).get("endReason").asText()).isEqualTo("CANCELLED");
        assertThat(callLinesAfter(conversationId, alice, beforeSeq)).singleElement()
                .satisfies(message -> assertThat(message.path("content").asText())
                        .isEqualTo("CALL|CANCELLED|0"));
    }

    @Test
    void ringingTimeoutWritesMissedCallHistory() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        long beforeSeq = latestSeq(getMessages(conversationId, alice, null));
        Connection caller = connect(alice);
        Connection callee = connect(bob);
        UUID callId = UUID.randomUUID();

        send(caller, conversationId, new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        awaitEvent(callee, "CALL_INVITE");
        JsonNode callerTimeout = awaitEvent(caller, "CALL_TIMEOUT");
        JsonNode calleeTimeout = awaitEvent(callee, "CALL_TIMEOUT");
        assertThat(callerTimeout.path("callId").asText()).isEqualTo(callId.toString());
        assertThat(callerTimeout.path("fromUserId").asText()).isEqualTo(alice.id().toString());
        assertThat(callerTimeout.path("payload").path("reason").asText()).isEqualTo("MISSED");
        assertThat(calleeTimeout.path("callId").asText()).isEqualTo(callId.toString());
        assertThat(calleeTimeout.path("fromUserId").asText()).isEqualTo(alice.id().toString());
        assertThat(calleeTimeout.path("payload").path("reason").asText()).isEqualTo("MISSED");

        JsonNode history = getJson("/api/v1/conversations/" + conversationId + "/calls", alice);
        assertThat(callHistoryItem(history, callId).get("endReason").asText()).isEqualTo("MISSED");
        assertThat(callLinesAfter(conversationId, alice, beforeSeq)).singleElement()
                .satisfies(message -> assertThat(message.path("content").asText())
                        .isEqualTo("CALL|MISSED|0"));
    }

    @Test
    void lastParticipantDisconnectEndsTheCallForThePeer() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        long beforeSeq = latestSeq(getMessages(conversationId, alice, null));
        Connection caller = connect(alice);
        Connection callee = connect(bob);
        UUID callId = UUID.randomUUID();
        send(caller, conversationId, new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        awaitEvent(callee, "CALL_INVITE");
        send(callee, conversationId, new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        awaitEvent(caller, "CALL_ACCEPTED");
        awaitEvent(callee, "CALL_ACCEPTED");

        callee.session().disconnect();
        assertThat(awaitEvent(caller, "CALL_ENDED").path("payload").path("reason").asText()).isEqualTo("FAILED");
        JsonNode history = getJson("/api/v1/conversations/" + conversationId + "/calls", alice);
        assertThat(callHistoryItem(history, callId).get("endReason").asText()).isEqualTo("FAILED");
        assertThat(callLinesAfter(conversationId, alice, beforeSeq)).singleElement()
                .satisfies(message -> assertThat(message.path("content").asText())
                        .startsWith("CALL|FAILED|"));
    }

    @Test
    void rejectsNonMembersGroupsAndOversizedSdp() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        Connection caller = connect(alice);
        Connection callee = connect(bob);
        Connection thirdParty = connect(carol);
        UUID callId = UUID.randomUUID();
        send(caller, conversationId, new CallSignal(CallSignal.Type.INVITE, callId, null, null));
        awaitEvent(callee, "CALL_INVITE");
        send(callee, conversationId, new CallSignal(CallSignal.Type.ACCEPT, callId, null, null));
        awaitEvent(caller, "CALL_ACCEPTED");
        awaitEvent(callee, "CALL_ACCEPTED");

        send(thirdParty, conversationId, new CallSignal(CallSignal.Type.END, callId, null, null));
        assertThat(awaitError(thirdParty.errors()).code()).isEqualTo("NOT_MEMBER");
        send(caller, conversationId, new CallSignal(CallSignal.Type.SDP, callId,
                new CallSignal.Sdp("offer", "x".repeat(16 * 1024 + 1)), null));
        assertThat(awaitError(caller.errors()).code()).isEqualTo("VALIDATION_FAILED");
        send(caller, conversationId, new CallSignal(null, UUID.randomUUID(), null, null));
        assertThat(awaitError(caller.errors()).code()).isEqualTo("VALIDATION_FAILED");

        JsonNode group = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Call test group", "memberIds", List.of(bob.id().toString(), carol.id().toString())))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID groupId = UUID.fromString(group.get("id").asText());
        send(caller, groupId, new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null));
        assertThat(awaitError(caller.errors()).code()).isEqualTo("NOT_MEMBER");

        send(caller, conversationId, new CallSignal(CallSignal.Type.END, callId, null, null));
        awaitEvent(caller, "CALL_ENDED");
        awaitEvent(callee, "CALL_ENDED");
    }

    private Connection connect(TestUser user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", bearer(user));
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                        (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sessions.add(session);
        BlockingQueue<JsonNode> calls = new LinkedBlockingQueue<>();
        BlockingQueue<WsErrorResponse> errors = new LinkedBlockingQueue<>();
        subscribeCalls(session, calls);
        subscribe(session, "/user/queue/errors", WsErrorResponse.class, errors);
        Thread.sleep(400);
        return new Connection(session, calls, errors);
    }

    private void subscribeCalls(StompSession session, BlockingQueue<JsonNode> queue) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/user/queue/calls");
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                try {
                    queue.add(objectMapper.readTree((byte[]) payload));
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Call frame was not valid JSON", exception);
                }
            }
        });
    }

    private <T> void subscribe(StompSession session, String destination, Class<T> type, BlockingQueue<T> queue) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders frameHeaders) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders frameHeaders, Object payload) {
                queue.add(type.cast(payload));
            }
        });
    }

    private static void send(Connection connection, UUID conversationId, CallSignal signal) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/calls/" + conversationId + "/signal");
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        connection.session().send(headers, signal);
    }

    private static JsonNode awaitEvent(Connection connection, String expectedType) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            JsonNode event = connection.calls().poll(100, TimeUnit.MILLISECONDS);
            if (event != null && expectedType.equals(event.path("eventType").asText())) {
                return event;
            }
            WsErrorResponse error = connection.errors().poll();
            if (error != null) {
                throw new AssertionError("Received STOMP error " + error.code()
                        + " while waiting for " + expectedType);
            }
        }
        throw new AssertionError("Timed out waiting for " + expectedType);
    }

    private static WsErrorResponse awaitError(BlockingQueue<WsErrorResponse> queue) throws Exception {
        WsErrorResponse error = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        return error;
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8498" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID openDirect(TestUser first, TestUser second) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", second.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private JsonNode getJson(String path, TestUser user) throws Exception {
        String json = mockMvc.perform(get(path).header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode getMessages(UUID conversationId, TestUser user, Long afterSeq) throws Exception {
        var request = get("/api/v1/conversations/" + conversationId + "/messages")
                .header("Authorization", bearer(user)).param("limit", "100");
        if (afterSeq != null) {
            request.param("afterSeq", afterSeq.toString());
        }
        String json = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static long latestSeq(JsonNode messages) {
        long latest = 0;
        for (JsonNode item : messages.path("items")) {
            latest = Math.max(latest, item.path("seq").asLong());
        }
        return latest;
    }

    private List<JsonNode> callLinesAfter(UUID conversationId, TestUser user, long afterSeq) throws Exception {
        JsonNode messages = getMessages(conversationId, user, afterSeq);
        List<JsonNode> callLines = new ArrayList<>();
        for (JsonNode item : messages.path("items")) {
            if ("SYSTEM".equals(item.path("type").asText())
                    && item.path("content").asText().startsWith("CALL|")) {
                callLines.add(item);
            }
        }
        return callLines;
    }

    private static JsonNode callHistoryItem(JsonNode history, UUID callId) {
        for (JsonNode item : history.path("items")) {
            if (callId.toString().equals(item.path("callId").asText())) {
                return item;
            }
        }
        throw new AssertionError("Call history did not include " + callId);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    private static final class RawByteMessageConverter implements MessageConverter {
        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            return payload instanceof byte[] ? MessageBuilder.createMessage(payload, headers) : null;
        }

        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            return targetClass == byte[].class && message.getPayload() instanceof byte[]
                    ? message.getPayload() : null;
        }
    }
}
