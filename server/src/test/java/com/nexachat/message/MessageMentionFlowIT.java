package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.notification.MockFcmGateway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(printOnlyOnFailure = false)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageMentionFlowIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockFcmGateway mockFcmGateway;

    private record TestUser(UUID id, String accessToken) { }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private TestUser dave;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "ITM" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
    }

    @BeforeEach
    void clearPreviousTestConversations() {
        jdbcTemplate.update("DELETE FROM conversations WHERE created_by = ?", alice.id());
        mockFcmGateway.clear();
    }

    @Test
    void mentionMetadataRoundTripsThroughHistoryDeltaEditsAndTombstones() throws Exception {
        UUID conversationId = openGroup(alice, bob, carol);
        JsonNode created = objectMapper.readTree(send(conversationId, alice, "Hi @Bob",
                        List.of(mention(bob, 3, 4)), false)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID messageId = UUID.fromString(created.get("id").asText());
        long seq = created.get("seq").asLong();
        assertMentions(created, List.of(new ExpectedMention(bob.id(), 3, 4)));
        assertThat(created.get("mentionAll").asBoolean()).isFalse();

        JsonNode history = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertMentions(findById(history.get("items"), messageId),
                List.of(new ExpectedMention(bob.id(), 3, 4)));

        send(conversationId, alice, "@Bob https://example.com",
                List.of(mention(bob, 0, 4)), false).andExpect(status().isCreated());
        JsonNode archive = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/archive", conversationId)
                        .header("Authorization", bearer(alice)).param("type", "LINK"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(archive.get("items")).hasSize(1);
        assertThat(archive.toString()).doesNotContain("mentions", bob.id().toString());
        JsonNode aiOptOut = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/ai-opt-out", conversationId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aiOptOut.has("mentions")).isFalse();

        JsonNode updated = objectMapper.readTree(edit(conversationId, messageId, alice,
                        "Meet @Carol", List.of(mention(carol, 5, 6)), false)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertMentions(updated, List.of(new ExpectedMention(carol.id(), 5, 6)));

        JsonNode delta = sync(conversationId, alice, seq, Instant.parse("1970-01-01T00:00:00Z"));
        assertMentions(delta.get("deltas").get(0).get("editedMessages").get(0),
                List.of(new ExpectedMention(carol.id(), 5, 6)));

        assertError("VALIDATION_FAILED", 400,
                editContentOnly(conversationId, messageId, alice, "short"));
        mockMvc.perform(delete("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        JsonNode afterDelete = objectMapper.readTree(mockMvc.perform(get(
                                "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode tombstone = findById(afterDelete.get("items"), messageId);
        assertThat(tombstone.get("content").isNull()).isTrue();
        assertThat(tombstone.get("mentions")).isEmpty();
        assertThat(tombstone.get("mentionAll").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_mentions WHERE message_id = ?", Integer.class, messageId))
                .isZero();

        JsonNode clearCandidate = objectMapper.readTree(send(conversationId, alice, "@Bob",
                        List.of(mention(bob, 0, 4)), false)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode cleared = objectMapper.readTree(edit(conversationId,
                        UUID.fromString(clearCandidate.get("id").asText()), alice,
                        "short", List.of(), false)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cleared.get("mentions")).isEmpty();
        assertThat(cleared.get("mentionAll").asBoolean()).isFalse();
    }

    @Test
    void validationAndAuthorizationRejectInvalidOrOutOfGroupMentions() throws Exception {
        UUID groupId = openGroup(alice, bob, carol);

        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "abcd", List.of(mention(bob, 0, 2), mention(bob, 2, 2)), false));
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "abcdefgh", List.of(mention(bob, 0, 5), mention(carol, 4, 2)), false));
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "abcdefgh", List.of(mention(bob, 7, 2)), false));
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "abcd", List.of(mention(bob, 1, 0)), false));
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "😀", List.of(mention(bob, 0, 1)), false));
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "@Dave", List.of(mention(dave, 0, 5)), false));

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(mention(bob, i, 1));
        }
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "x".repeat(21), tooMany, false));

        UUID directId = openDirect(alice, bob);
        assertError("VALIDATION_FAILED", 400,
                send(directId, alice, "@Bob", List.of(mention(bob, 0, 4)), false));
        assertError("VALIDATION_FAILED", 400,
                send(directId, alice, "@all", List.of(), true));
        assertError("NOT_ADMIN", 403,
                send(groupId, bob, "@all", List.of(), true));

        JsonNode all = objectMapper.readTree(send(groupId, alice, "@all", List.of(), true)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("mentionAll").asBoolean()).isTrue();

        assertError("NOT_MEMBER", 403,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages", groupId)
                        .header("Authorization", bearer(dave))));

        send(groupId, bob, "Before leaving", List.of(), false).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/members/{userId}", groupId, bob.id())
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isNoContent());
        assertError("VALIDATION_FAILED", 400,
                send(groupId, alice, "@Bob", List.of(mention(bob, 0, 4)), false));
        assertError("NOT_MEMBER", 403,
                mockMvc.perform(get("/api/v1/conversations/{id}/messages", groupId)
                        .header("Authorization", bearer(bob))));
    }

    @Test
    void mutedMembersReceiveOnlyDirectOrAllMentionsWithMentionFlag() throws Exception {
        UUID conversationId = openGroup(alice, bob, carol);
        String bobToken = "mention-bob-" + UUID.randomUUID();
        String carolToken = "mention-carol-" + UUID.randomUUID();
        registerDeviceToken(bob, bobToken);
        registerDeviceToken(carol, carolToken);
        mockMvc.perform(put("/api/v1/conversations/{id}/mute", conversationId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("forever", true))))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        send(conversationId, alice, "ordinary", List.of(), false)
                .andExpect(status().isCreated());
        assertThat(mockFcmGateway.getSentPushes().stream().map(MockFcmGateway.SentPush::token))
                .contains(carolToken).doesNotContain(bobToken);

        mockFcmGateway.clear();
        send(conversationId, alice, "@Bob hi", List.of(mention(bob, 0, 4)), false)
                .andExpect(status().isCreated());
        assertPush(bobToken, true);
        assertPush(carolToken, false);

        mockFcmGateway.clear();
        send(conversationId, alice, "@all", List.of(), true).andExpect(status().isCreated());
        assertPush(bobToken, true);
        assertPush(carolToken, true);

        mockFcmGateway.clear();
        assertError("NOT_ADMIN", 403,
                send(conversationId, bob, "@all", List.of(), true));
        assertThat(mockFcmGateway.getSentPushes()).isEmpty();
    }

    private void assertPush(String token, boolean mentioned) {
        List<MockFcmGateway.SentPush> matching = mockFcmGateway.getSentPushes().stream()
                .filter(push -> push.token().equals(token)).toList();
        assertThat(matching).isNotEmpty();
        if (mentioned) {
            assertThat(matching).anySatisfy(push ->
                    assertThat(push.data().get("mentioned")).isEqualTo("true"));
        } else {
            assertThat(matching).allSatisfy(push ->
                    assertThat(push.data()).doesNotContainKey("mentioned"));
        }
    }

    private record ExpectedMention(UUID userId, int offset, int length) { }

    private void assertMentions(JsonNode message, List<ExpectedMention> expected) {
        assertThat(message.get("mentions")).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode actual = message.get("mentions").get(i);
            ExpectedMention value = expected.get(i);
            assertThat(actual.get("userId").asText()).isEqualTo(value.userId().toString());
            assertThat(actual.get("offset").asInt()).isEqualTo(value.offset());
            assertThat(actual.get("length").asInt()).isEqualTo(value.length());
        }
    }

    private ResultActions send(UUID conversationId, TestUser sender, String content,
                               List<Map<String, Object>> mentions, boolean mentionAll) throws Exception {
        return sendRaw(conversationId, sender, content, mentions, mentionAll);
    }

    private ResultActions sendRaw(UUID conversationId, TestUser sender, String content,
                                  List<Map<String, Object>> mentions, Boolean mentionAll) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("clientMessageId", UUID.randomUUID().toString());
        request.put("type", "TEXT");
        request.put("content", content);
        if (mentions != null) {
            request.put("mentions", mentions);
        }
        if (mentionAll != null) {
            request.put("mentionAll", mentionAll);
        }
        return mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                .header("Authorization", bearer(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions edit(UUID conversationId, UUID messageId, TestUser editor, String content,
                               List<Map<String, Object>> mentions, Boolean mentionAll) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("content", content);
        if (mentions != null) {
            request.put("mentions", mentions);
        }
        if (mentionAll != null) {
            request.put("mentionAll", mentionAll);
        }
        return mockMvc.perform(patch("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                .header("Authorization", bearer(editor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions editContentOnly(UUID conversationId, UUID messageId,
                                          TestUser editor, String content) throws Exception {
        return mockMvc.perform(patch("/api/v1/conversations/{c}/messages/{m}", conversationId, messageId)
                .header("Authorization", bearer(editor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", content))));
    }

    private JsonNode sync(UUID conversationId, TestUser user, long lastSeq, Instant since) throws Exception {
        Map<String, Object> request = Map.of(
                "watermarks", List.of(Map.of("conversationId", conversationId.toString(), "lastSeq", lastSeq)),
                "since", since.toString());
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/sync")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private UUID openGroup(TestUser admin, TestUser... members) throws Exception {
        List<String> memberIds = java.util.Arrays.stream(members)
                .map(member -> member.id().toString()).toList();
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Mentions " + UUID.randomUUID(), "memberIds", memberIds))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private UUID openDirect(TestUser first, TestUser second) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", second.id().toString()))))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8490" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private void registerDeviceToken(TestUser user, String token) throws Exception {
        mockMvc.perform(post("/api/v1/users/device-token")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", token, "platform", "ANDROID", "deviceLabel", "Mention IT"))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> mention(TestUser user, int offset, int length) {
        return mention(user.id(), offset, length);
    }

    private static Map<String, Object> mention(UUID userId, int offset, int length) {
        return Map.of("userId", userId.toString(), "offset", offset, "length", length);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    private static JsonNode findById(JsonNode array, UUID id) {
        for (JsonNode item : array) {
            if (id.toString().equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Expected id not present in response");
    }

    private void assertError(String code, int expectedStatus, ResultActions response) throws Exception {
        MvcResult result = response.andExpect(status().is(expectedStatus)).andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("error").get("code").asText()).isEqualTo(code);
    }
}
