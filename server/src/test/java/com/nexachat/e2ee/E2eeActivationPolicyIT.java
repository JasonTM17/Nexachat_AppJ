package com.nexachat.e2ee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.AuthService;
import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.RegisterRequest;
import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Default-off activation gate: with {@code nexachat.e2ee.v1-activation-enabled}
 * unset the server denies new false->true toggles and reports the capability as
 * false, while legacy enabled conversations keep reading, sending and turning
 * off. {@link E2eeFlowIT} covers the opted-in contract; this suite must never
 * set the property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2eeActivationPolicyIT {

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

    private record TestUser(UUID id, String accessToken) {
    }

    private record Pair(TestUser a, TestUser b, UUID conversationId,
            UUID aDeviceId, UUID bDeviceId) {
    }

    @BeforeAll
    void cleanFixtures() {
        ItPhoneRanges.clean(jdbcTemplate, E2eeActivationPolicyIT.class);
    }

    private TestUser registerUser(String displayName) {
        // Register through the service bean: MockMvc calls share one source IP
        // and the auth rate limiter 429s long ITs that create many users.
        String phone = ItPhoneRanges.nextPhone(E2eeActivationPolicyIT.class);
        AuthResponse response = authService.register(
                new RegisterRequest(phone, "secret123", displayName), null);
        return new TestUser(response.user().id(), response.accessToken());
    }

    private static String publicKey(int seed) {
        byte[] key = new byte[32];
        key[0] = (byte) seed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private void putDevice(TestUser user, UUID deviceId, String publicKey) throws Exception {
        mockMvc.perform(put("/api/v1/e2ee/devices/" + deviceId)
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("publicKey", publicKey))))
                .andExpect(status().isOk());
    }

    /** Two fresh users, one active device each, and their direct conversation. */
    private Pair newPair(String tag) throws Exception {
        TestUser a = registerUser(tag + "-a");
        TestUser b = registerUser(tag + "-b");
        UUID aDeviceId = UUID.randomUUID();
        UUID bDeviceId = UUID.randomUUID();
        putDevice(a, aDeviceId, publicKey(20));
        putDevice(b, bDeviceId, publicKey(21));
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", b.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString()).get("id").asText());
        return new Pair(a, b, conversationId, aDeviceId, bDeviceId);
    }

    private ResultActions getState(UUID conversationId, TestUser user) throws Exception {
        return mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/e2ee")
                .header("Authorization", "Bearer " + user.accessToken()));
    }

    private ResultActions putState(UUID conversationId, TestUser user, boolean enabled)
            throws Exception {
        return mockMvc.perform(put("/api/v1/conversations/" + conversationId + "/e2ee")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("enabled", enabled))));
    }

    private int messageCount(UUID conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id=?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    @Test
    void capabilityIsFalseAndActivationIsDeniedWithoutSideEffects() throws Exception {
        Pair pair = newPair("deny");
        getState(pair.conversationId(), pair.a())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.experimentalV1ActivationAllowed").value(false));

        int messagesBefore = messageCount(pair.conversationId());
        putState(pair.conversationId(), pair.a(), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_V1_ACTIVATION_DISABLED"));
        putState(pair.conversationId(), pair.b(), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_V1_ACTIVATION_DISABLED"));

        // Denial writes nothing: flag stays off and no SYSTEM line appears.
        getState(pair.conversationId(), pair.a())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.experimentalV1ActivationAllowed").value(false));
        assertThat(messageCount(pair.conversationId())).isEqualTo(messagesBefore);
    }

    @Test
    void membershipAndTypeGuardsRunBeforeThePolicy() throws Exception {
        Pair pair = newPair("order");
        // Unauthenticated requests never reach the service.
        mockMvc.perform(put("/api/v1/conversations/" + pair.conversationId() + "/e2ee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isUnauthorized());
        // Non-members get NOT_MEMBER, not the policy denial.
        TestUser outsider = registerUser("order-outsider");
        putState(pair.conversationId(), outsider, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        // Non-DIRECT conversations keep the type guard ahead of the policy.
        MvcResult group = mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "grp", "memberIds",
                                        List.of(pair.b().id().toString())))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        String groupId = objectMapper.readTree(group.getResponse().getContentAsString())
                .get("id").asText();
        mockMvc.perform(put("/api/v1/conversations/" + groupId + "/e2ee")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_UNSUPPORTED_CONVERSATION"));
    }

    @Test
    void legacyEnabledConversationKeepsWorkingAndCanStillDisable() throws Exception {
        Pair pair = newPair("legacy");
        // Seed the pre-gate state: enabled conversation under a default-off gate.
        jdbcTemplate.update("UPDATE conversations SET e2ee_enabled=true WHERE id=?",
                pair.conversationId());

        getState(pair.conversationId(), pair.a())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.experimentalV1ActivationAllowed").value(false));

        // A true->true call is an idempotent no-op, not a new activation:
        // it must not post a SYSTEM line either.
        int messagesBeforeIdempotent = messageCount(pair.conversationId());
        putState(pair.conversationId(), pair.a(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(messageCount(pair.conversationId()))
                .isEqualTo(messagesBeforeIdempotent);

        // Encrypted sends and history are unaffected by the gate.
        mockMvc.perform(post("/api/v1/conversations/" + pair.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + pair.a().accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "type", "TEXT",
                                "content", "e2ee1:dGVzdA==",
                                "encryption", "E2EE_V1",
                                "e2eeEnvelopes", List.of(
                                        // Opaque 8-byte base64url fixture buffers, not
                                        // cryptographic wrapped keys.
                                        Map.of("deviceId", pair.aDeviceId().toString(),
                                                "wrappedKey", "AAAAAAAAAAA"),
                                        Map.of("deviceId", pair.bDeviceId().toString(),
                                                "wrappedKey", "AQEBAQEBAQE"))))))
                .andExpect(status().is2xxSuccessful());

        // Explicit disable still works even though new activation is denied.
        putState(pair.conversationId(), pair.a(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        putState(pair.conversationId(), pair.a(), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_V1_ACTIVATION_DISABLED"));

        // The previously encrypted message is still in history.
        MvcResult history = mockMvc
                .perform(get("/api/v1/conversations/" + pair.conversationId() + "/messages")
                        .header("Authorization", "Bearer " + pair.a().accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(history.getResponse().getContentAsString())
                .get("items");
        // The fake ciphertext fixture is a storage/retention check, not a
        // cryptographic proof: the exact content and both original per-device
        // envelopes must survive the disable untouched.
        JsonNode retained = null;
        for (JsonNode item : items) {
            if ("E2EE_V1".equals(item.path("encryption").asText())
                    && "e2ee1:dGVzdA==".equals(item.path("content").asText())) {
                retained = item;
            }
        }
        assertThat(retained).isNotNull();
        Map<String, String> envelopes = new HashMap<>();
        for (JsonNode envelope : retained.path("e2eeEnvelopes")) {
            envelopes.put(envelope.path("deviceId").asText(),
                    envelope.path("wrappedKey").asText());
        }
        assertThat(envelopes).containsExactlyInAnyOrderEntriesOf(Map.of(
                pair.aDeviceId().toString(), "AAAAAAAAAAA",
                pair.bDeviceId().toString(), "AQEBAQEBAQE"));
    }
}
