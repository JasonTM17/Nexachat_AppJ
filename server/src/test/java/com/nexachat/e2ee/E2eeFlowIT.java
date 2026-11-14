package com.nexachat.e2ee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.nexachat.auth.AuthService;
import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.RegisterRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the E2EE v1 contract: device registration, the
 * conversation toggle, encrypted send rules, read surfaces, and every
 * exclusion (search, inbox preview, reply preview, edit, summary, ask).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2eeFlowIT {

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AuthService authService;

    private record TestUser(UUID id, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private UUID aliceDevice;
    private UUID bobDevice;

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, E2eeFlowIT.class);
        String tag = "ITZ" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        aliceDevice = UUID.randomUUID();
        bobDevice = UUID.randomUUID();
        putDevice(alice, aliceDevice, publicKey(1)).andExpect(status().isOk());
        putDevice(bob, bobDevice, publicKey(2)).andExpect(status().isOk());
    }

    private static String publicKey(int seed) {
        byte[] key = new byte[32];
        key[0] = (byte) seed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private TestUser registerUser(String displayName) {
        // Register through the service bean: MockMvc calls share one source IP
        // and the auth rate limiter 429s long ITs that create many users.
        String phone = ItPhoneRanges.nextPhone(E2eeFlowIT.class);
        AuthResponse response = authService.register(
                new RegisterRequest(phone, "secret123", displayName), null);
        return new TestUser(response.user().id(), displayName, response.accessToken());
    }

    private ResultActions putDevice(TestUser user, UUID deviceId, String publicKey) throws Exception {
        return mockMvc.perform(put("/api/v1/e2ee/devices/" + deviceId)
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("publicKey", publicKey))));
    }

    /** Two fresh users, one active device each, and their direct conversation. */
    private record Pair(TestUser a, TestUser b, UUID aDevice, UUID bDevice, UUID conversationId) {
    }

    private Pair newPair(String tag) throws Exception {
        TestUser a = registerUser(tag + "-a");
        TestUser b = registerUser(tag + "-b");
        UUID aDevice = UUID.randomUUID();
        UUID bDevice = UUID.randomUUID();
        putDevice(a, aDevice, publicKey(20)).andExpect(status().isOk());
        putDevice(b, bDevice, publicKey(21)).andExpect(status().isOk());
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", b.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString()).get("id").asText());
        return new Pair(a, b, aDevice, bDevice, conversationId);
    }

    private Pair newEncryptedPair(String tag) throws Exception {
        Pair pair = newPair(tag);
        enableE2ee(pair);
        return pair;
    }

    private void enableE2ee(Pair pair) throws Exception {
        enableE2ee(pair.conversationId(), pair.a().accessToken());
    }

    private void enableE2ee(UUID conversationId, String token) throws Exception {
        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    private ResultActions send(UUID conversationId, TestUser sender, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                .header("Authorization", "Bearer " + sender.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> encryptedSend(UUID clientMessageId, UUID deviceId) {
        return Map.of("clientMessageId", clientMessageId.toString(), "type", "TEXT",
                "content", "e2ee1:dGVzdA==", "encryption", "E2EE_V1",
                "e2eeEnvelopes", List.of(
                        Map.of("deviceId", deviceId.toString(), "wrappedKey", "d3JhcHBlZA")),
                "attachmentIds", List.of());
    }

    // ---------- device API ----------

    @Test
    void devicePutIsIdempotentAndConflictsOnMismatch() throws Exception {
        TestUser user = registerUser("dev-idem");
        UUID device = UUID.randomUUID();
        putDevice(user, device, publicKey(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(device.toString()));
        // Same id, same owner, same key → idempotent 200.
        putDevice(user, device, publicKey(7)).andExpect(status().isOk());
        // Same id, different key → conflict.
        putDevice(user, device, publicKey(8)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_DEVICE_CONFLICT"));
        // Same id, another user → conflict.
        putDevice(bob, device, publicKey(7)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_DEVICE_CONFLICT"));
    }

    @Test
    void deviceDeleteIsIdempotentAndRevokes() throws Exception {
        TestUser user = registerUser("dev-del");
        UUID device = UUID.randomUUID();
        putDevice(user, device, publicKey(9)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/e2ee/devices/" + device)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/e2ee/devices/" + device)
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isNoContent());
        // A revoked id cannot be re-registered, even by the same owner.
        putDevice(user, device, publicKey(9)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_DEVICE_CONFLICT"));
    }

    @Test
    void deviceLimitRejectsEleventhActiveDevice() throws Exception {
        TestUser user = registerUser("dev-limit");
        for (int i = 0; i < 10; i++) {
            putDevice(user, UUID.randomUUID(), publicKey(10 + i)).andExpect(status().isOk());
        }
        putDevice(user, UUID.randomUUID(), publicKey(99)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_DEVICE_LIMIT"));
    }

    @Test
    void devicePutRejectsMalformedKey() throws Exception {
        putDevice(alice, UUID.randomUUID(), "not base64!!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putDevice(alice, UUID.randomUUID(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---------- conversation toggle ----------

    @Test
    void toggleRequiresDevicesAndDirectMembership() throws Exception {
        TestUser lone = registerUser("no-devices");
        TestUser peer = registerUser("peer");
        MvcResult direct = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + lone.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", peer.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(direct.getResponse().getContentAsString()).get("id").asText());
        // Neither member has a device → enabling refused.
        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", "Bearer " + lone.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_PEER_NOT_READY"));
        // Non-member cannot read or toggle.
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", "Bearer " + carolToken()))
                .andExpect(status().is4xxClientError());
    }

    private String carolTokenCache;

    private String carolToken() throws Exception {
        if (carolTokenCache == null) {
            carolTokenCache = registerUser("carol" + System.nanoTime()).accessToken();
        }
        return carolTokenCache;
    }

    @Test
    void toggleFlipsStatePostsSystemMessageAndListsDevices() throws Exception {
        Pair pair = newEncryptedPair("toggle");
        UUID conversationId = pair.conversationId();
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", "Bearer " + pair.b().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.devices.length()").value(2));
        // The toggle posts exactly one SYSTEM line; a no-op PUT posts none.
        Integer systems = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id=? AND type='SYSTEM'",
                Integer.class, conversationId);
        assertThat(systems).isEqualTo(1);
        String body = jdbcTemplate.queryForObject(
                "SELECT content FROM messages WHERE conversation_id=? AND type='SYSTEM'",
                String.class, conversationId);
        assertThat(body).contains("turned on end-to-end encryption");
        mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/e2ee")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk());
        systems = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id=? AND type='SYSTEM'",
                Integer.class, conversationId);
        assertThat(systems).isEqualTo(1);
        // Conversation DTO exposes the flag.
        MvcResult inbox = mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + pair.a().accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entry = inboxEntry(inbox, conversationId);
        assertThat(entry.get("e2eeEnabled").asBoolean()).isTrue();
    }

    @Test
    void groupConversationRejectsE2ee() throws Exception {
        MvcResult group = mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "grp", "memberIds", List.of(bob.id().toString())))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        String groupId = objectMapper.readTree(group.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(get("/api/v1/conversations/" + groupId + "/e2ee")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_UNSUPPORTED_CONVERSATION"));
    }

    // ---------- send rules ----------

    @Test
    void plaintextConversationRejectsEncryptionFields() throws Exception {
        Pair pair = newPair("plain");
        send(pair.conversationId(), pair.a(), encryptedSend(UUID.randomUUID(), pair.aDevice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_NOT_ENABLED"));
        send(pair.conversationId(), pair.a(), Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "plain", "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", pair.aDevice().toString(), "wrappedKey", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void encryptedConversationEnforcesSendRules() throws Exception {
        Pair pair = newEncryptedPair("rules");
        UUID conversationId = pair.conversationId();
        TestUser alice = pair.a();
        String aliceDevice = pair.aDevice().toString();
        // Missing encryption → E2EE_REQUIRED.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:abc", "attachmentIds", List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_REQUIRED"));
        // Non-TEXT → E2EE_MEDIA_UNSUPPORTED.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "IMAGE", "encryption", "E2EE_V1",
                        "attachmentIds", List.of(UUID.randomUUID().toString()),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", aliceDevice, "wrappedKey", "abc"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_MEDIA_UNSUPPORTED"));
        // Wrong scheme → VALIDATION_FAILED.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:abc", "encryption", "PGP",
                        "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", aliceDevice, "wrappedKey", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // Missing e2ee1: prefix → VALIDATION_FAILED.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "plaintext", "encryption", "E2EE_V1",
                        "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", aliceDevice, "wrappedKey", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // Mentions → VALIDATION_FAILED.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:abc", "encryption", "E2EE_V1",
                        "attachmentIds", List.of(), "mentionAll", true,
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", aliceDevice, "wrappedKey", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        // Unknown device → E2EE_STALE_DEVICES.
        send(conversationId, alice, Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:abc", "encryption", "E2EE_V1",
                        "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", UUID.randomUUID().toString(), "wrappedKey", "abc"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
    }

    @Test
    void encryptedSendPersistsAndReplaysIdempotently() throws Exception {
        Pair pair = newEncryptedPair("persist");
        UUID clientMessageId = UUID.randomUUID();
        MvcResult first = send(pair.conversationId(), pair.a(), encryptedSend(clientMessageId, pair.bDevice()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encryption").value("E2EE_V1"))
                .andExpect(jsonPath("$.e2eeEnvelopes[0].deviceId").value(pair.bDevice().toString()))
                .andExpect(jsonPath("$.e2eeEnvelopes[0].wrappedKey").value("d3JhcHBlZA"))
                .andReturn();
        String messageId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();
        // Replay returns the stored row unchanged.
        send(pair.conversationId(), pair.a(), encryptedSend(clientMessageId, pair.bDevice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(messageId))
                .andExpect(jsonPath("$.encryption").value("E2EE_V1"));
        // History read-back carries the same fields.
        MvcResult history = mockMvc.perform(get("/api/v1/conversations/" + pair.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + pair.b().accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(history.getResponse().getContentAsString()).get("items");
        JsonNode stored = null;
        for (JsonNode item : items) {
            if (messageId.equals(item.get("id").asText())) {
                stored = item;
            }
        }
        assertThat(stored).isNotNull();
        assertThat(stored.get("encryption").asText()).isEqualTo("E2EE_V1");
        assertThat(stored.get("e2eeEnvelopes").get(0).get("deviceId").asText())
                .isEqualTo(pair.bDevice().toString());
    }

    // ---------- exclusions ----------

    @Test
    void encryptedMessagesAreExcludedFromSearchAndInboxSnippet() throws Exception {
        Pair pair = newEncryptedPair("excl");
        UUID conversationId = pair.conversationId();
        send(conversationId, pair.a(), Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:c2VjcmV0", "encryption", "E2EE_V1",
                        "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", pair.bDevice().toString(), "wrappedKey", "abc"))))
                .andExpect(status().isCreated());
        // Search never matches ciphertext.
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages/search")
                        .param("q", "e2ee1")
                        .header("Authorization", "Bearer " + pair.a().accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // Inbox preview hides the snippet but flags the encrypted last message.
        MvcResult inbox = mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + pair.a().accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entry = inboxEntry(inbox, conversationId);
        assertThat(entry.get("lastMessageEncrypted").asBoolean()).isTrue();
        assertThat(entry.get("lastMessage").get("contentSnippet").isNull()).isTrue();
    }

    private JsonNode inboxEntry(MvcResult result, UUID conversationId) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode item : items) {
            if (conversationId.toString().equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Conversation " + conversationId + " not in inbox page");
    }

    @Test
    void editOfEncryptedMessageIsRefused() throws Exception {
        Pair pair = newEncryptedPair("edit");
        UUID conversationId = pair.conversationId();
        MvcResult sent = send(conversationId, pair.a(), encryptedSend(UUID.randomUUID(), pair.bDevice()))
                .andExpect(status().isCreated())
                .andReturn();
        String messageId = objectMapper.readTree(sent.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(patch("/api/v1/conversations/" + conversationId + "/messages/" + messageId)
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "e2ee1:bmV3"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_EDIT_UNSUPPORTED"));
    }

    @Test
    void replyPreviewHidesCiphertext() throws Exception {
        Pair pair = newEncryptedPair("reply");
        UUID conversationId = pair.conversationId();
        MvcResult original = send(conversationId, pair.a(), encryptedSend(UUID.randomUUID(), pair.bDevice()))
                .andExpect(status().isCreated())
                .andReturn();
        String originalId = objectMapper.readTree(original.getResponse().getContentAsString())
                .get("id").asText();
        send(conversationId, pair.b(), Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:cmVwbHk=", "encryption", "E2EE_V1",
                        "replyToId", originalId, "attachmentIds", List.of(),
                        "e2eeEnvelopes", List.of(
                                Map.of("deviceId", pair.aDevice().toString(), "wrappedKey", "abc"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replyTo.id").value(originalId))
                .andExpect(jsonPath("$.replyTo.encrypted").value(true))
                .andExpect(jsonPath("$.replyTo.content").value((Object) null));
    }

    @Test
    void aiEndpointsRefuseEncryptedConversations() throws Exception {
        Pair pair = newEncryptedPair("ai");
        UUID conversationId = pair.conversationId();
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("scope", "LAST_N", "limit", 50))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_AI_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ask")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", "hi?"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_AI_UNAVAILABLE"));
    }
}
