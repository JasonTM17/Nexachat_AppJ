package com.nexachat.robustness;

import com.nexachat.testutil.ItPhoneRanges;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.nexachat.robustness.RobustnessResponseAssert.isClientErrorWithCode;
import static com.nexachat.robustness.RobustnessResponseAssert.isSafeClientResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Table-driven hostile input across the REST surface. One shared fixture
 * (two users, direct conversation, one message) keeps the suite fast.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestInputRobustnessIT extends RobustnessITBase {

    private static final String RTL = "\u200F";
    private static final String ZWJ = "\u200D";
    private static final String COMBINING = "e\u0301";
    private static final String EMOJI_ZWJ = "👨\u200D👩\u200D👧";
    private static final String FOUR_BYTE = "𠮷";

    private Registered alice;
    private Registered bob;
    private Registered stranger;
    private UUID conversationId;
    private UUID messageId;

    @BeforeAll
    void seedFixture() throws Exception {
        resetSharedFixtureDatabase();
        alice = register(ItPhoneRanges.phoneFor(RestInputRobustnessIT.class, "+84907100001"), "Alice Fuzz");
        bob = register(ItPhoneRanges.phoneFor(RestInputRobustnessIT.class, "+84907100002"), "Bob Fuzz");
        stranger = register(ItPhoneRanges.phoneFor(RestInputRobustnessIT.class, "+84907100003"), "Stranger Fuzz");
        conversationId = openDirect(alice, bob.userId()).id();
        messageId = sendText(alice, conversationId, "fixture seed");
    }

    // --- Path UUID corruption (every controller using UUID path vars) ---

    /** Values that reach the controller's UUID conversion and must be a 400. */
    static Stream<String> invalidConversationIds() {
        return Stream.of(
                "not-a-uuid",
                "00000000-0000-0000-0000",
                "12345"
        );
    }

    /**
     * Values that the servlet path handling rejects before any handler runs
     * (path traversal, NUL, decoded pipe). There is no application envelope for
     * these: the contract is only that they are a 4xx, never a routed success.
     */
    static Stream<String> illegalUrlSegments() {
        return Stream.of(
                "../../etc/passwd",
                "%00",
                "v2%7C1%7C0"
        );
    }

    @ParameterizedTest
    @MethodSource("invalidConversationIds")
    void invalidConversationIdInPathReturns4xx(String badId) throws Exception {
        mockMvc.perform(get("/api/v1/conversations/" + badId + "/messages")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/conversations/" + badId + "/messages")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"TEXT\",\"content\":\"x\"}"))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @MethodSource("illegalUrlSegments")
    void illegalUrlSegmentsNeverReachAHandler(String badSegment) throws Exception {
        mockMvc.perform(get("/api/v1/conversations/" + badSegment + "/messages")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/conversations/" + badSegment + "/messages")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"TEXT\",\"content\":\"x\"}"))
                .andExpect(status().is4xxClientError());
    }

    @ParameterizedTest
    @MethodSource("invalidConversationIds")
    void invalidConversationIdOnNestedGetRoutesReturns4xx(String badId) throws Exception {
        String token = alice.accessToken();
        List<String> paths = List.of(
                "/api/v1/conversations/" + badId + "/messages/search?q=hi",
                "/api/v1/conversations/" + badId + "/pins",
                "/api/v1/conversations/" + badId + "/polls",
                "/api/v1/conversations/" + badId + "/polls/changes",
                "/api/v1/conversations/" + badId + "/reminders",
                "/api/v1/conversations/" + badId + "/archive",
                "/api/v1/conversations/" + badId + "/ai-opt-out",
                "/api/v1/conversations/" + badId + "/calls",
                "/api/v1/conversations/" + badId + "/invite-link",
                "/api/v1/conversations/" + badId + "/join-requests",
                "/api/v1/conversations/" + badId + "/join-requests/me");
        for (String path : paths) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        }
    }

    /**
     * {@code /ask} and {@code /summary} are POST-only, so probing them with a
     * GET would stop at 405 before the path variable is ever converted.
     */
    @ParameterizedTest
    @MethodSource("invalidConversationIds")
    void invalidConversationIdOnNestedPostRoutesReturns4xx(String badId) throws Exception {
        String token = alice.accessToken();
        List<String> actions = List.of("/ask", "/summary", "/read", "/delivered");
        for (String action : actions) {
            mockMvc.perform(post("/api/v1/conversations/" + badId + action)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        }
    }

    // --- Query params: limit / seq / pagination ---

    static Stream<Arguments> hostileHistoryQueryParams() {
        return Stream.of(
                Arguments.of("limit", "-1"),
                Arguments.of("limit", "0"),
                Arguments.of("limit", String.valueOf(Integer.MAX_VALUE)),
                Arguments.of("limit", "not-int"),
                Arguments.of("beforeSeq", "-99"),
                Arguments.of("afterSeq", "-1"),
                Arguments.of("beforeSeq", "1"),
                Arguments.of("afterSeq", "1"),
                Arguments.of("beforeSeq", String.valueOf(Long.MAX_VALUE))
        );
    }

    @Test
    void messageHistoryRejectsMutuallyExclusiveCursors() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .param("beforeSeq", "1")
                        .param("afterSeq", "1")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @MethodSource("hostileHistoryQueryParams")
    void messageHistoryHostileQueryParams(String name, String value) throws Exception {
        long before = messageCount(conversationId);
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .param(name, value)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isSafeClientResponse());
        assertThat(messageCount(conversationId)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5", "0", "99999", "abc", "v1|bad|cursor"})
    void inboxHostilePaginationParams(String cursorOrLimit) throws Exception {
        mockMvc.perform(get("/api/v1/conversations")
                        .param("limit", cursorOrLimit)
                        .param("cursor", cursorOrLimit)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(isSafeClientResponse());
    }

    // --- Body validation: send / edit / read / delivered ---

    @Test
    void sendMessageRejectedBodiesLeaveDbUnchanged() throws Exception {
        long before = messageCount(conversationId);
        String base = "/api/v1/conversations/" + conversationId + "/messages";

        postRaw(base, "{not-json", alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw(base, "null", alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw(base, "[]", alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));
        postRaw(base, "{}", alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        String hugeContent = "{\"clientMessageId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"TEXT\",\"content\":\"" + "x".repeat(10_001) + "\"}";
        postRaw(base, hugeContent, alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        String unicodePayload = objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "type", "TEXT",
                "content", RTL + COMBINING + EMOJI_ZWJ + FOUR_BYTE + ZWJ));
        postRaw(base, unicodePayload, alice.accessToken()).andExpect(status().isCreated());

        mockMvc.perform(post(base)
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(isClientErrorWithCode("UNSUPPORTED_MEDIA_TYPE"));

        // Five rejected bodies, one accepted unicode message, one rejected
        // content type: exactly one new row, never a partial write per probe.
        assertThat(messageCount(conversationId)).isEqualTo(before + 1);
    }

    @Test
    void editAndReadWatermarksRejectGarbage() throws Exception {
        long before = messageCount(conversationId);

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/conversations/" + conversationId
                        + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":null}"))
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postJson("/api/v1/conversations/" + conversationId + "/read",
                Map.of("upToSeq", -1), alice.accessToken())
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postJson("/api/v1/conversations/" + conversationId + "/delivered",
                Map.of("upToSeq", -1), alice.accessToken())
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postJson("/api/v1/conversations/" + conversationId + "/read",
                Map.of("upToSeq", 9_999_999), alice.accessToken())
                .andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        assertThat(messageCount(conversationId)).isEqualTo(before);
    }

    // --- Cross-tenant IDs ---

    @Test
    void strangerCannotMutateFixtureConversation() throws Exception {
        String token = stranger.accessToken();
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("NOT_MEMBER"));

        postJson("/api/v1/conversations/" + conversationId + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "intrude"),
                token)
                .andExpect(isClientErrorWithCode("NOT_MEMBER"));

        mockMvc.perform(delete("/api/v1/conversations/" + conversationId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(isClientErrorWithCode("NOT_MEMBER"));
    }

    // --- Sequential idempotent send (concurrency lives in IdempotencyConcurrencyIT) ---

    @Test
    void duplicateClientMessageIdViaRestIsIdempotent() throws Exception {
        UUID clientMessageId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "clientMessageId", clientMessageId.toString(),
                "type", "TEXT",
                "content", "idempotent");
        String path = "/api/v1/conversations/" + conversationId + "/messages";
        postJson(path, payload, alice.accessToken()).andExpect(status().isCreated());
        postJson(path, payload, alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND client_message_id = ?",
                Long.class, conversationId, clientMessageId);
        assertThat(rows).isEqualTo(1);
    }

    // --- Misc routes (authenticated smoke + hostile query/body) ---

    static Stream<Arguments> restRoutesWithHostileInputs() {
        UUID randomId = UUID.randomUUID();
        return Stream.of(
                Arguments.of("GET", "/api/v1/conversations", null, "inbox"),
                Arguments.of("GET", "/api/v1/users/me", null, "profile"),
                Arguments.of("GET", "/api/v1/users/search?q=" + RTL + "a", null, "user-search"),
                Arguments.of("GET", "/api/v1/contacts", null, "contacts-list"),
                Arguments.of("GET", "/api/v1/labels", null, "labels-list"),
                Arguments.of("GET", "/api/v1/rag/stats", null, "rag-stats"),
                Arguments.of("GET", "/api/v1/calls/ice-config", null, "ice-config"),
                Arguments.of("GET", "/api/v1/conversations/" + randomId + "/messages", null, "foreign-conv"),
                Arguments.of("POST", "/api/v1/sync", "{}", "sync-empty"),
                Arguments.of("POST", "/api/v1/chat/sync", "{}", "chat-sync-empty")
        );
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("restRoutesWithHostileInputs")
    void authenticatedRouteSurvivesHostileInput(String method, String path, String body, String label)
            throws Exception {
        var builder = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(body == null ? "" : body);
            default -> throw new IllegalArgumentException(method);
        };
        builder.header("Authorization", "Bearer " + alice.accessToken());
        mockMvc.perform(builder).andExpect(isSafeClientResponse());
    }

    @Test
    void pollReminderLabelBodiesRejectOversizeAndWrongTypes() throws Exception {
        String conv = conversationId.toString();
        postRaw("/api/v1/conversations/" + conv + "/polls",
                "{\"question\":\"q\",\"options\":[\"a\"],\"multiChoice\":false}",
                alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postRaw("/api/v1/conversations/" + conv + "/reminders",
                "{\"title\":\"\",\"dueAt\":\"not-instant\"}",
                alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postRaw("/api/v1/labels",
                "{\"name\":\"" + "L".repeat(300) + "\",\"colorId\":\"BLUE\"}",
                alice.accessToken()).andExpect(isClientErrorWithCode("VALIDATION_FAILED"));

        postJson("/api/v1/conversations/" + conv + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "after polls"),
                alice.accessToken()).andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedAccessNever5xx() throws Exception {
        mockMvc.perform(get("/api/v1/conversations"))
                .andExpect(isClientErrorWithCode("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/conversations/direct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(isClientErrorWithCode("UNAUTHORIZED"));
    }

    @Test
    void duplicateJsonKeysStillParsedSafely() throws Exception {
        String dupKeys = "{\"phone\":\"+84907100999\",\"phone\":\"+84907100888\","
                + "\"password\":\"secret123\",\"displayName\":\"Dup\"}";
        postRaw("/api/v1/auth/register", dupKeys, null, remoteAddr(uniqueIp()))
                .andExpect(isSafeClientResponse());
    }

    private org.springframework.test.web.servlet.ResultActions postRaw(
            String path, String json, String token, RequestPostProcessor extra) throws Exception {
        var builder = post(path)
                .with(extra)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }
}
