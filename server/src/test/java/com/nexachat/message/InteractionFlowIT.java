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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the phase-10 interactions slice (TC-INT-01..04)
 * against a real PostgreSQL — same env-JDBC pattern as {@code MessageFlowIT}.
 *
 * <p>Covers: reaction toggle add→add-by-peer→remove with viewer-relative
 * {@code byMe} in history, member/non-member authZ, GROUP non-admin pin →
 * 403 {@code NOT_ADMIN}, the fourth pin → 409 {@code PIN_LIMIT} (then unpin
 * frees a slot), and the {@code replyTo} preview carried end-to-end by send
 * + history (plus cross-conversation / missing reply targets).
 *
 * <p>{@code PER_CLASS}: users register once — the auth register bucket is
 * shared across the suite. Test phones use the {@code +8492%} prefix —
 * distinct from sibling suites ({@code +8490%}/{@code +8491%}) so no
 * {@code @BeforeAll} cleanup ever deletes another suite's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InteractionFlowIT {

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

    @BeforeAll
    void registerUsers() throws Exception {
        // Ordered cleanup respecting FKs: pinned_messages.pinned_by has NO
        // cascade, then conversations (cascades messages → reactions/pins),
        // then attachments by owner, then the users themselves.
        String like = "phone LIKE '+8492%'";
        jdbcTemplate.update(
                "DELETE FROM pinned_messages WHERE pinned_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
        String tag = "ITN" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8492" + String.format("%07d", random.nextInt(10_000_000));
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

    private JsonNode history(UUID conversationId, TestUser reader) throws Exception {
        MvcResult result = getJson(
                "/api/v1/conversations/" + conversationId + "/messages?limit=50",
                reader.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode historyItem(UUID conversationId, TestUser reader, UUID messageId)
            throws Exception {
        for (JsonNode item : history(conversationId, reader).get("items")) {
            if (item.get("id").asText().equals(messageId.toString())) {
                return item;
            }
        }
        return null;
    }

    // ============================================================ TC-INT-01

    @Test
    void reactTwiceTogglesOffAndViewersSeeByMe() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID messageId = sendRest(conversationId, alice, "react to me");

        // alice reacts 👍 → 204, one row stored
        postJson("/api/v1/messages/" + messageId + "/reactions", alice.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(1);

        // bob sees count=1, byMe=false; alice sees byMe=true
        JsonNode forBob = historyItem(conversationId, bob, messageId);
        assertThat(forBob.get("reactions")).hasSize(1);
        assertThat(forBob.get("reactions").get(0).get("emoji").asText()).isEqualTo("👍");
        assertThat(forBob.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(forBob.get("reactions").get(0).get("byMe").asBoolean()).isFalse();
        JsonNode forAlice = historyItem(conversationId, alice, messageId);
        assertThat(forAlice.get("reactions").get(0).get("byMe").asBoolean()).isTrue();

        // bob piles on the same emoji → count=2
        postJson("/api/v1/messages/" + messageId + "/reactions", bob.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isNoContent());
        JsonNode afterBob = historyItem(conversationId, bob, messageId);
        assertThat(afterBob.get("reactions").get(0).get("count").asInt()).isEqualTo(2);
        assertThat(afterBob.get("reactions").get(0).get("byMe").asBoolean()).isTrue();

        // alice re-posts the same emoji → toggle OFF, bob's row survives
        postJson("/api/v1/messages/" + messageId + "/reactions", alice.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class, messageId)).isEqualTo(1);
        JsonNode finalState = historyItem(conversationId, bob, messageId);
        assertThat(finalState.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(finalState.get("reactions").get(0).get("byMe").asBoolean()).isTrue();

        // negative paths: non-member 403, blank emoji 400
        postJson("/api/v1/messages/" + messageId + "/reactions", carol.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/messages/" + messageId + "/reactions", alice.accessToken(),
                Map.of("emoji", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/messages/" + UUID.randomUUID() + "/reactions", alice.accessToken(),
                Map.of("emoji", "👍"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MESSAGE_NOT_FOUND"));
    }

    // ============================================================ TC-INT-02

    @Test
    void nonAdminPinInGroupIsForbiddenButDirectMemberPins() throws Exception {
        // GROUP: alice is creator (ADMIN), bob a plain MEMBER.
        MvcResult group = postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "Pin team", "memberIds", List.of(bob.id().toString())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID groupId = UUID.fromString(objectMapper
                .readTree(group.getResponse().getContentAsString()).get("id").asText());
        UUID groupMessageId = sendRest(groupId, alice, "pin me in the group");

        // member (non-admin) pin attempt → 403 NOT_ADMIN; nothing stored
        postJson("/api/v1/conversations/" + groupId + "/pins", bob.accessToken(),
                Map.of("messageId", groupMessageId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pinned_messages WHERE conversation_id = ?",
                Integer.class, groupId)).isEqualTo(0);

        // admin pins → 204; any member reads the list
        postJson("/api/v1/conversations/" + groupId + "/pins", alice.accessToken(),
                Map.of("messageId", groupMessageId.toString()))
                .andExpect(status().isNoContent());
        MvcResult pins = getJson("/api/v1/conversations/" + groupId + "/pins", bob.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pinList = objectMapper.readTree(pins.getResponse().getContentAsString());
        assertThat(pinList).hasSize(1);
        assertThat(pinList.get(0).get("messageId").asText()).isEqualTo(groupMessageId.toString());
        assertThat(pinList.get(0).get("pinnedBy").asText()).isEqualTo(alice.id().toString());
        assertThat(pinList.get(0).get("message").get("content").asText())
                .isEqualTo("pin me in the group");

        // DIRECT: plain member pins freely — no admin concept in 1:1.
        // Dedicated bob↔carol room: the alice↔bob pair is shared (open-direct
        // is idempotent by direct_key) and TC-INT-03 fills ITS pin slots —
        // pin counts would leak across tests on a shared conversation.
        UUID directId = openDirectConversation(bob, carol);
        UUID directMessageId = sendRest(directId, bob, "pin me direct");
        postJson("/api/v1/conversations/" + directId + "/pins", carol.accessToken(),
                Map.of("messageId", directMessageId.toString()))
                .andExpect(status().isNoContent());

        // non-member surfaces: alice is not in the bob↔carol room → 403 both ways
        getJson("/api/v1/conversations/" + directId + "/pins", alice.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson("/api/v1/conversations/" + directId + "/pins", alice.accessToken(),
                Map.of("messageId", directMessageId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    // ============================================================ TC-INT-03

    @Test
    void fourthPinHitsPinLimitAndUnpinFreesTheSlot() throws Exception {
        // Dedicated alice↔carol room — pin counts must not leak across tests.
        UUID conversationId = openDirectConversation(alice, carol);
        UUID m1 = sendRest(conversationId, alice, "one");
        UUID m2 = sendRest(conversationId, alice, "two");
        UUID m3 = sendRest(conversationId, alice, "three");
        UUID m4 = sendRest(conversationId, alice, "four");

        for (UUID id : List.of(m1, m2, m3)) {
            postJson("/api/v1/conversations/" + conversationId + "/pins", alice.accessToken(),
                    Map.of("messageId", id.toString()))
                    .andExpect(status().isNoContent());
        }

        // 4th distinct pin → 409 PIN_LIMIT
        postJson("/api/v1/conversations/" + conversationId + "/pins", alice.accessToken(),
                Map.of("messageId", m4.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PIN_LIMIT"));

        // re-pinning an ALREADY pinned message is a no-op, not a limit hit
        postJson("/api/v1/conversations/" + conversationId + "/pins", alice.accessToken(),
                Map.of("messageId", m1.toString()))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pinned_messages WHERE conversation_id = ?",
                Integer.class, conversationId)).isEqualTo(3);

        // pinning a foreign message → VALIDATION_FAILED
        UUID otherConversation = openDirectConversation(alice, bob);
        UUID foreign = sendRest(otherConversation, alice, "not yours");
        postJson("/api/v1/conversations/" + conversationId + "/pins", alice.accessToken(),
                Map.of("messageId", foreign.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // unpin frees a slot → the 4th message now pins
        deleteJson("/api/v1/conversations/" + conversationId + "/pins/" + m1,
                alice.accessToken())
                .andExpect(status().isNoContent());
        postJson("/api/v1/conversations/" + conversationId + "/pins", alice.accessToken(),
                Map.of("messageId", m4.toString()))
                .andExpect(status().isNoContent());

        MvcResult pins = getJson("/api/v1/conversations/" + conversationId + "/pins",
                alice.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pinList = objectMapper.readTree(pins.getResponse().getContentAsString());
        assertThat(pinList).hasSize(3);
        assertThat(pinList.findValuesAsText("messageId"))
                .containsExactlyInAnyOrder(m2.toString(), m3.toString(), m4.toString());
    }

    // ============================================================ TC-INT-04

    @Test
    void replySendReturnsReplyToPreviewEndToEnd() throws Exception {
        UUID conversationId = openDirectConversation(alice, bob);
        UUID originalId = sendRest(conversationId, alice, "original question?");

        // bob replies via REST — 201 carries the embedded preview + flat id
        MvcResult reply = postJson(
                "/api/v1/conversations/" + conversationId + "/messages", bob.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "answer!",
                        "replyToId", originalId.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode replyBody = objectMapper.readTree(reply.getResponse().getContentAsString());
        assertThat(replyBody.get("replyToId").asText()).isEqualTo(originalId.toString());
        JsonNode preview = replyBody.get("replyTo");
        assertThat(preview.get("id").asText()).isEqualTo(originalId.toString());
        assertThat(preview.get("senderId").asText()).isEqualTo(alice.id().toString());
        assertThat(preview.get("senderDisplayName").asText()).isEqualTo(alice.displayName());
        assertThat(preview.get("type").asText()).isEqualTo("TEXT");
        assertThat(preview.get("content").asText()).isEqualTo("original question?");
        assertThat(preview.get("deleted").asBoolean()).isFalse();

        // history hydrates the same preview
        UUID replyId = UUID.fromString(replyBody.get("id").asText());
        JsonNode stored = historyItem(conversationId, alice, replyId);
        assertThat(stored.get("replyTo").get("id").asText()).isEqualTo(originalId.toString());
        assertThat(stored.get("replyTo").get("content").asText())
                .isEqualTo("original question?");
        assertThat(stored.get("reactions")).isEmpty();

        // replyTo in another conversation → VALIDATION_FAILED; missing → 404
        UUID otherConversation = openDirectConversation(alice, carol);
        UUID foreign = sendRest(otherConversation, alice, "elsewhere");
        postJson("/api/v1/conversations/" + conversationId + "/messages", bob.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "bad reply",
                        "replyToId", foreign.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/conversations/" + conversationId + "/messages", bob.accessToken(),
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "ghost reply",
                        "replyToId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MESSAGE_NOT_FOUND"));
    }
}
