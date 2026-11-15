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

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        // This suite exercises the enabled contract, so it opts the
        // experimental activation gate in explicitly; the default stays off.
        registry.add("nexachat.e2ee.v1-activation-enabled", () -> "true");
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
    @Autowired
    private E2eeDeviceService e2eeDeviceService;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

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

    private Map<String, Object> encryptedSend(UUID clientMessageId, UUID... deviceIds) {
        return Map.of("clientMessageId", clientMessageId.toString(), "type", "TEXT",
                "content", "e2ee1:dGVzdA==", "encryption", "E2EE_V1",
                "e2eeEnvelopes", envelopes(deviceIds),
                "attachmentIds", List.of());
    }

    private static List<Map<String, String>> envelopes(UUID... deviceIds) {
        return java.util.Arrays.stream(deviceIds)
                .map(deviceId -> Map.of(
                        "deviceId", deviceId.toString(), "wrappedKey", "d3JhcHBlZA"))
                .toList();
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
        send(pair.conversationId(), pair.a(),
                        encryptedSend(UUID.randomUUID(), pair.aDevice(), pair.bDevice()))
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
    void encryptedSendRequiresExactMemberDeviceSet() throws Exception {
        Pair pair = newEncryptedPair("exact");
        UUID conversationId = pair.conversationId();
        // The complete set — both members' active devices — is accepted.
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(), pair.aDevice(), pair.bDevice()))
                .andExpect(status().isCreated());
        // A send missing the peer's device leaves it unable to decrypt.
        send(conversationId, pair.a(), encryptedSend(UUID.randomUUID(), pair.aDevice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
        // The sender's own current device is part of the required set: the
        // client wraps a copy of the content key for itself.
        send(conversationId, pair.a(), encryptedSend(UUID.randomUUID(), pair.bDevice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
        // An active device that does not belong to a member is extra.
        TestUser outsider = registerUser("foreign");
        UUID foreignDevice = UUID.randomUUID();
        putDevice(outsider, foreignDevice, publicKey(40)).andExpect(status().isOk());
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(),
                                pair.aDevice(), pair.bDevice(), foreignDevice))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
    }

    @Test
    void envelopeSetTracksMemberDeviceChanges() throws Exception {
        Pair pair = newEncryptedPair("grow");
        UUID conversationId = pair.conversationId();
        // The recipient enrolls a second device after the sender's snapshot:
        // the old device set is now incomplete and must be rejected.
        UUID bobSecond = UUID.randomUUID();
        putDevice(pair.b(), bobSecond, publicKey(41)).andExpect(status().isOk());
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(), pair.aDevice(), pair.bDevice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
        // Covering the new device satisfies the check.
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(),
                                pair.aDevice(), pair.bDevice(), bobSecond))
                .andExpect(status().isCreated());
        // A second sender device joins the required set too.
        UUID aliceSecond = UUID.randomUUID();
        putDevice(pair.a(), aliceSecond, publicKey(42)).andExpect(status().isOk());
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(),
                                pair.aDevice(), pair.bDevice(), bobSecond))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
        // Revocation shrinks the required set: the shrunken set is accepted
        // and the revoked device id is rejected when still covered.
        mockMvc.perform(delete("/api/v1/e2ee/devices/" + bobSecond)
                        .header("Authorization", "Bearer " + pair.b().accessToken()))
                .andExpect(status().isNoContent());
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(),
                                pair.aDevice(), aliceSecond, pair.bDevice()))
                .andExpect(status().isCreated());
        send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(),
                                pair.aDevice(), aliceSecond, pair.bDevice(), bobSecond))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_STALE_DEVICES"));
    }

    @Test
    void sendRacingDeviceRegistrationCannotMissCommittedDevice() throws Exception {
        Pair pair = newEncryptedPair("race");
        UUID bobSecondDevice = UUID.randomUUID();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger registrationPid =
                new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A registration held open at commit time: it owns the recipient's
            // user row lock (the one registration serializes on) while its
            // device row stays invisible.
            Future<?> registration = pool.submit(() -> {
                new org.springframework.transaction.support.TransactionTemplate(
                        transactionManager).executeWithoutResult(status -> {
                            e2eeDeviceService.register(
                                    pair.b().id(), bobSecondDevice, publicKey(43));
                            registrationPid.set(jdbcTemplate.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class));
                            registered.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
                return null;
            });
            assertThat(registered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> sendFuture = pool.submit(() ->
                    send(pair.conversationId(), pair.a(),
                                    encryptedSend(UUID.randomUUID(),
                                            pair.aDevice(), pair.bDevice()))
                            .andReturn());
            // The send must be observed waiting on the registration's owner
            // row lock: without that fact the test only measures timing luck.
            com.nexachat.testutil.TestWaits.until(
                    "send transaction waits on the in-flight registration",
                    () -> backendsBlockedBy(registrationPid.get()) >= 1);
            release.countDown();
            registration.get(30, TimeUnit.SECONDS);
            MvcResult result = sendFuture.get(30, TimeUnit.SECONDS);
            // The device committed before the send's check ran, so the stale
            // envelope set is rejected — never accepted while incomplete.
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("error").path("code").asText())
                    .isEqualTo("E2EE_STALE_DEVICES");
            // Once the directory caught up, the exact set is accepted.
            send(pair.conversationId(), pair.a(),
                            encryptedSend(UUID.randomUUID(),
                                    pair.aDevice(), pair.bDevice(), bobSecondDevice))
                    .andExpect(status().isCreated());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private int backendsBlockedBy(int pid) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity"
                        + " WHERE datname = current_database()"
                        + " AND ? = ANY(pg_blocking_pids(pid))",
                Integer.class, pid);
        return count == null ? 0 : count;
    }

    @Test
    void encryptedSendIntoGroupDoesNotLockOtherMemberRows() throws Exception {
        TestUser memberB = registerUser("glock-b");
        TestUser memberC = registerUser("glock-c");
        MvcResult group = mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "grp",
                                "memberIds", List.of(
                                        memberB.id().toString(), memberC.id().toString())))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID groupId = UUID.fromString(objectMapper
                .readTree(group.getResponse().getContentAsString()).get("id").asText());

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection gate = lockConnection();
             Connection probe = lockConnection()) {
            // Park the send on the group conversation row: by the time it
            // waits there, every users-row lock it took is already held and
            // observable from a second session.
            int gatePid = lockRowForUpdate(gate, groupId);
            Future<MvcResult> sendFuture = pool.submit(() ->
                    send(groupId, alice, encryptedSend(UUID.randomUUID(), aliceDevice))
                            .andReturn());
            com.nexachat.testutil.TestWaits.until(
                    "send transaction waits on the group conversation row",
                    () -> backendsBlockedBy(gatePid) >= 1);
            // Other members' rows accept FOR NO KEY UPDATE instantly — the
            // rejected send never fanned out to the member roster.
            assertThat(tryRowLockNowait(probe, memberB.id(), "NO KEY UPDATE")).isTrue();
            assertThat(tryRowLockNowait(probe, memberC.id(), "NO KEY UPDATE")).isTrue();
            // The sender row IS locked: its FOR KEY SHARE blocks a FOR UPDATE
            // probe, proving the send reached the lock section at all.
            assertThat(tryRowLockNowait(probe, alice.id(), "UPDATE")).isFalse();

            gate.rollback();
            MvcResult result = sendFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.path("error").path("code").asText())
                    .isEqualTo("E2EE_NOT_ENABLED");
        } finally {
            pool.shutdownNow();
        }
    }

    private Connection lockConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
        connection.setAutoCommit(false);
        return connection;
    }

    /** Holds {@code FOR UPDATE} on the conversation row; returns the backend pid. */
    private int lockRowForUpdate(Connection connection, UUID conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM conversations WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, conversationId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** True when the row lock was granted, false on {@code lock_not_available} (55P03). */
    private boolean tryRowLockNowait(Connection connection, UUID userId, String mode)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? FOR " + mode + " NOWAIT")) {
            statement.setObject(1, userId);
            statement.executeQuery().close();
            return true;
        } catch (SQLException failure) {
            if ("55P03".equals(failure.getSQLState())) {
                return false;
            }
            throw failure;
        }
    }

    @Test
    void encryptedSendPersistsAndReplaysIdempotently() throws Exception {
        Pair pair = newEncryptedPair("persist");
        UUID clientMessageId = UUID.randomUUID();
        MvcResult first = send(pair.conversationId(), pair.a(),
                        encryptedSend(clientMessageId, pair.aDevice(), pair.bDevice()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encryption").value("E2EE_V1"))
                .andExpect(jsonPath("$.e2eeEnvelopes[0].deviceId").value(pair.aDevice().toString()))
                .andExpect(jsonPath("$.e2eeEnvelopes[0].wrappedKey").value("d3JhcHBlZA"))
                .andReturn();
        String messageId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();
        // Replay returns the stored row unchanged.
        send(pair.conversationId(), pair.a(),
                        encryptedSend(clientMessageId, pair.aDevice(), pair.bDevice()))
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
        assertThat(stored.get("e2eeEnvelopes")).hasSize(2);
        assertThat(stored.get("e2eeEnvelopes").get(0).get("deviceId").asText())
                .isEqualTo(pair.aDevice().toString());
        assertThat(stored.get("e2eeEnvelopes").get(1).get("deviceId").asText())
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
                        "e2eeEnvelopes", envelopes(pair.aDevice(), pair.bDevice())))
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
        MvcResult sent = send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(), pair.aDevice(), pair.bDevice()))
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
        MvcResult original = send(conversationId, pair.a(),
                        encryptedSend(UUID.randomUUID(), pair.aDevice(), pair.bDevice()))
                .andExpect(status().isCreated())
                .andReturn();
        String originalId = objectMapper.readTree(original.getResponse().getContentAsString())
                .get("id").asText();
        send(conversationId, pair.b(), Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "e2ee1:cmVwbHk=", "encryption", "E2EE_V1",
                        "replyToId", originalId, "attachmentIds", List.of(),
                        "e2eeEnvelopes", envelopes(pair.aDevice(), pair.bDevice())))
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

    @Test
    void aiEndpointsDoNotRevealE2eeStateToNonMembers() throws Exception {
        Pair pair = newEncryptedPair("ai-nonmember");
        TestUser outsider = registerUser("outsider");
        UUID conversationId = pair.conversationId();
        // Membership must win over the e2ee check so outsiders cannot probe
        // whether a conversation is encrypted.
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                        .header("Authorization", "Bearer " + outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("scope", "LAST_N", "limit", 50))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/ask")
                        .header("Authorization", "Bearer " + outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("question", "hi?"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void concurrentRegistrationsSerializePerOwnerCap() throws Exception {
        TestUser user = registerUser("dev-race");
        for (int i = 0; i < 9; i++) {
            putDevice(user, UUID.randomUUID(), publicKey(10 + i))
                    .andExpect(status().isOk());
        }
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<MvcResult> putFirst = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return putDevice(user, first, publicKey(30)).andReturn();
            };
            Callable<MvcResult> putSecond = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return putDevice(user, second, publicKey(31)).andReturn();
            };
            Future<MvcResult> futureFirst = pool.submit(putFirst);
            Future<MvcResult> futureSecond = pool.submit(putSecond);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            MvcResult firstResult = futureFirst.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = futureSecond.get(30, TimeUnit.SECONDS);

            List<MvcResult> results = List.of(firstResult, secondResult);
            long successes = results.stream()
                    .filter(r -> r.getResponse().getStatus() == 200).count();
            assertThat(successes).as("exactly one registration may pass the cap")
                    .isEqualTo(1);
            MvcResult loser = results.stream()
                    .filter(r -> r.getResponse().getStatus() != 200)
                    .findFirst().orElseThrow();
            JsonNode body = objectMapper.readTree(
                    loser.getResponse().getContentAsString());
            assertThat(body.path("error").path("code").asText())
                    .isEqualTo("E2EE_DEVICE_LIMIT");
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM e2ee_devices"
                            + " WHERE user_id = ? AND revoked_at IS NULL",
                    Integer.class, user.id());
            assertThat(active).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void devicePutRejectsDeletedOwner() throws Exception {
        TestUser ghost = registerUser("ghost");
        // Soft-delete is the tombstone state produced by account deletion;
        // stamping it directly avoids the OTP round-trip and keeps the test
        // scoped to the service-level owner check.
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?",
                ghost.id());
        ApiException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> e2eeDeviceService.register(ghost.id(), UUID.randomUUID(),
                        publicKey(50)),
                ApiException.class);
        assertThat(thrown).isNotNull();
        assertThat(thrown.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

}
