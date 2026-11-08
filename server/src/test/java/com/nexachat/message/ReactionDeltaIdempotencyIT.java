package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the reaction delta events, idempotent replay and
 * per-user device tokens against a real PostgreSQL (V10).
 *
 * <p>Covers: a removal is visible in the delta derived from
 * {@code reaction_events}; the idempotent {@code PUT}/{@code DELETE} reaction
 * verbs; {@code Idempotency-Key} replay and reuse rejection; the reaction-delta
 * bound/cursor; a non-member reading no events; the {@code EXPLAIN} plan of the
 * delta query; and a shared device token surviving a second account's
 * registration.
 *
 * <p>{@code PER_CLASS}: users register once. Test phones use the {@code +8497%}
 * prefix — distinct from sibling suites so no cleanup deletes their rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactionDeltaIdempotencyIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5432/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // Keep the reaction page small so the bound/cursor path is exercised.
        registry.add("nexachat.sync.max-reaction-updates", () -> "3");
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

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // never joins a conversation — the non-member probe
    private TestUser dave;  // second account on bob's shared device token

    @BeforeAll
    void registerUsers() throws Exception {
        String like = "phone LIKE '+8497%'";
        jdbcTemplate.update(
                "DELETE FROM pinned_messages WHERE pinned_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM user_device_tokens WHERE user_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);

        alice = registerUser("Delta Alice");
        bob = registerUser("Delta Bob");
        carol = registerUser("Delta Carol");
        dave = registerUser("Delta Dave");
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8497" + String.format("%07d", (int) (Math.random() * 10_000_000));
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

    private ResultActions putJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + accessToken));
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

    private JsonNode sync(String accessToken, UUID conversationId, Long lastSeq, Long reactionCursor,
                          Instant since) throws Exception {
        Map<String, Object> watermark = new java.util.HashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", lastSeq);
        if (reactionCursor != null) {
            watermark.put("reactionCursor", reactionCursor);
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("watermarks", List.of(watermark));
        body.put("limitPerConv", 50);
        if (since != null) {
            body.put("since", since.toString());
        }
        MvcResult result = postJson("/api/v1/sync", accessToken, body)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ============================================================ reaction delta

    @Test
    void removalIsVisibleInTheReactionDelta() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "react to me");

        // alice adds 👍 via the idempotent verb
        putJson("/api/v1/messages/" + messageId + "/reactions/👍", alice.accessToken())
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reaction_events WHERE message_id = ? AND action = 'ADD'",
                Integer.class, messageId)).isEqualTo(1);

        // bob syncs: the add is in the delta
        JsonNode delta = sync(bob.accessToken(), conversationId, 0L, null, Instant.now().minusSeconds(3600));
        JsonNode reactionDelta = delta.get("deltas").get(0);
        assertThat(reactionDelta.get("reactionUpdates")).hasSize(1);
        assertThat(reactionDelta.get("reactionUpdates").get(0).get("messageId").asText())
                .isEqualTo(messageId.toString());

        // alice removes it — the message_reactions row is gone but the event remains
        deleteJson("/api/v1/messages/" + messageId + "/reactions/👍", alice.accessToken())
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reaction_events WHERE message_id = ? AND action = 'REMOVE'",
                Integer.class, messageId)).isEqualTo(1);

        // bob syncs again from the same cursor: the removal is still reported
        JsonNode afterRemoval = sync(bob.accessToken(), conversationId, 0L, null,
                Instant.now().minusSeconds(3600));
        JsonNode removalDelta = afterRemoval.get("deltas").get(0);
        assertThat(removalDelta.get("reactionUpdates")).hasSize(1);
        assertThat(removalDelta.get("reactionUpdates").get(0).get("messageId").asText())
                .isEqualTo(messageId.toString());
        assertThat(removalDelta.get("reactionUpdates").get(0).get("reactions")).isEmpty();
    }

    @Test
    void reactionDeltaPageIsBoundedAndResumesByCursor() throws Exception {
        // Dedicated bob↔carol room: direct conversations are idempotent by pair,
        // so reusing alice↔bob would leak other tests' reactions into the delta.
        UUID conversationId = openDirectConversation(bob, carol);
        List<UUID> messageIds = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID messageId = sendRest(conversationId, bob, "message " + i);
            messageIds.add(messageId);
            putJson("/api/v1/messages/" + messageId + "/reactions/👍", bob.accessToken())
                    .andExpect(status().isNoContent());
        }

        Instant since = Instant.now().minusSeconds(3600);
        JsonNode first = sync(carol.accessToken(), conversationId, 0L, null, since);
        JsonNode firstDelta = first.get("deltas").get(0);
        assertThat(firstDelta.get("reactionUpdates")).hasSize(3);
        assertThat(firstDelta.get("hasMore").asBoolean()).isTrue();
        assertThat(firstDelta.get("reactionCursor").isNull()).isFalse();

        // Echo the cursor: the follow-up page must continue without skipping.
        long cursor = firstDelta.get("reactionCursor").asLong();
        JsonNode second = sync(carol.accessToken(), conversationId, 0L, cursor, since);
        JsonNode secondDelta = second.get("deltas").get(0);
        assertThat(secondDelta.get("reactionUpdates")).hasSize(2);
        assertThat(secondDelta.get("hasMore").asBoolean()).isFalse();
        assertThat(secondDelta.get("reactionCursor").isNull()).isTrue();

        List<String> seen = new java.util.ArrayList<>();
        for (JsonNode update : firstDelta.get("reactionUpdates")) {
            seen.add(update.get("messageId").asText());
        }
        for (JsonNode update : secondDelta.get("reactionUpdates")) {
            seen.add(update.get("messageId").asText());
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                messageIds.stream().map(UUID::toString).toList());
    }

    @Test
    void nonMemberReadsNoReactionEvents() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "members only");
        putJson("/api/v1/messages/" + messageId + "/reactions/👍", alice.accessToken())
                .andExpect(status().isNoContent());

        JsonNode delta = sync(carol.accessToken(), conversationId, 0L, null,
                Instant.now().minusSeconds(3600));
        // carol is not a member: the conversation is skipped entirely
        assertThat(delta.get("deltas")).isEmpty();
    }

    // ======================================================= idempotent replay

    @Test
    void toggleReplayWithSameKeyDoesNotToggleTwice() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "toggle me");
        String key = UUID.randomUUID().toString();

        postJson("/api/v1/messages/" + messageId + "/reactions", alice.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isNoContent());
        // first request with the key: added
        MvcResult first = mockMvc.perform(post("/api/v1/messages/" + messageId + "/reactions")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("emoji", "❤️"))))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(first).isNotNull();

        // replay with the SAME key but a different emoji → 409, state unchanged
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/reactions")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("emoji", "😂"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        // the stored reaction is still ❤️ (from the first keyed request)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ? AND emoji = '❤️'",
                Integer.class, messageId)).isEqualTo(1);
    }

    @Test
    void idempotentAddAndRemoveAreSafeToReplay() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "idempotent verbs");
        String addKey = UUID.randomUUID().toString();
        String removeKey = UUID.randomUUID().toString();
        String encoded = "👍";

        // PUT twice with the same key: one row, one ADD event
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/" + encoded)
                            .header("Authorization", "Bearer " + alice.accessToken())
                            .header("Idempotency-Key", addKey))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reaction_events WHERE message_id = ? AND action = 'ADD'",
                Integer.class, messageId)).isEqualTo(1);

        // DELETE twice with the same key: removed, one REMOVE event
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete("/api/v1/messages/" + messageId + "/reactions/" + encoded)
                            .header("Authorization", "Bearer " + alice.accessToken())
                            .header("Idempotency-Key", removeKey))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reaction_events WHERE message_id = ? AND action = 'REMOVE'",
                Integer.class, messageId)).isEqualTo(1);
    }

    @Test
    void sameKeyOnDifferentEndpointIsRejected() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "endpoint reuse");
        String key = UUID.randomUUID().toString();

        putJson("/api/v1/messages/" + messageId + "/reactions/👍", alice.accessToken())
                .andExpect(status().isNoContent());
        // first keyed PUT stores the key
        mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/👍")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isNoContent());
        // same key on the DELETE endpoint → 409
        mockMvc.perform(delete("/api/v1/messages/" + messageId + "/reactions/👍")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidAndOversizedKeysAreRejected() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "bad keys");

        mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/👍")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        String oversized = UUID.randomUUID() + "-padding-padding-padding-padding-padding";
        mockMvc.perform(put("/api/v1/messages/" + messageId + "/reactions/👍")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void pinReplayWithSameKeyIsIdempotent() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "pin me");
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/pins")
                            .header("Authorization", "Bearer " + alice.accessToken())
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("messageId", messageId.toString()))))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pinned_messages WHERE conversation_id = ? AND message_id = ?",
                Integer.class, conversationId, messageId)).isEqualTo(1);
    }

    // ============================================================ device tokens

    @Test
    void sharedDeviceTokenSurvivesAnotherAccountsRegistration() throws Exception {
        String sharedToken = "shared-device-token-" + UUID.randomUUID();
        postJson("/api/v1/users/device-token", bob.accessToken(),
                Map.of("token", sharedToken, "platform", "ANDROID", "deviceLabel", "Shared Phone"))
                .andExpect(status().isOk());
        postJson("/api/v1/users/device-token", dave.accessToken(),
                Map.of("token", sharedToken, "platform", "ANDROID", "deviceLabel", "Shared Phone"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_device_tokens WHERE token = ?",
                Integer.class, sharedToken)).isEqualTo(2);

        // logging out one account removes only that account's row
        mockMvc.perform(delete("/api/v1/users/device-token")
                        .header("Authorization", "Bearer " + bob.accessToken())
                        .param("token", sharedToken))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_device_tokens WHERE token = ?",
                Integer.class, sharedToken)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_device_tokens WHERE token = ? AND user_id = ?",
                Integer.class, sharedToken, dave.id())).isEqualTo(1);
    }

    // ============================================================== EXPLAIN

    @Test
    void reactionDeltaQueryUsesAnIndex() {
        UUID conversationId = UUID.randomUUID();
        String directKey = "rxp-" + conversationId;
        UUID userId = alice.id();
        try {
            jdbcTemplate.update("INSERT INTO conversations (id, type, direct_key, created_by)"
                    + " VALUES (?, 'DIRECT', ?, ?)", conversationId, directKey, userId);
            jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id)"
                    + " VALUES (?, ?)", conversationId, userId);
            jdbcTemplate.update("""
                    INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)
                    VALUES (?, ?, gen_random_uuid(), 'TEXT', 'seed')
                    """, conversationId, userId);
            UUID messageId = jdbcTemplate.queryForObject(
                    "SELECT id FROM messages WHERE conversation_id = ?", UUID.class, conversationId);

            // 20k events spread over ~139 days so the created_at range is selective.
            jdbcTemplate.update("""
                    INSERT INTO reaction_events (conversation_id, message_id, user_id, emoji, action, created_at)
                    SELECT ?, ?, ?, '👍', 'ADD', now() - (g * interval '10 minutes')
                    FROM generate_series(1, 20000) g
                    """, conversationId, messageId, userId);
            jdbcTemplate.update("ANALYZE reaction_events");

            Instant since = Instant.now().minusSeconds(3600);
            String plan = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN (ANALYZE, TIMING OFF)
                    SELECT id, message_id
                    FROM reaction_events
                    WHERE conversation_id = ?
                      AND created_at > ?
                      AND id > 0
                    ORDER BY id ASC
                    LIMIT 201
                    """, String.class, conversationId, java.sql.Timestamp.from(since)));

            assertThat(plan)
                    .as("the reaction delta query must be index-served")
                    .containsAnyOf("idx_reaction_events_conv_created", "idx_reaction_events_conv_id")
                    .doesNotContain("Seq Scan on reaction_events");
        } finally {
            jdbcTemplate.update("DELETE FROM reaction_events WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversation_members WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
    }
}
