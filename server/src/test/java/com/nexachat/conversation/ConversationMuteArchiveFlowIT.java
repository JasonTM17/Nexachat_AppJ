package com.nexachat.conversation;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.notification.MockFcmGateway;
import org.junit.jupiter.api.AfterAll;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * End-to-end coverage of the per-member mute/archive slice against a real
 * PostgreSQL: REST member/non-member authZ, idempotent state changes, fail-closed
 * body validation, inbox filtering and flags, archive-aware paging, delta-sync
 * flags and FCM push gating.
 *
 * <p>{@code PER_CLASS}: the three users are registered once — the auth register
 * bucket (10 / 5 min / IP) is shared across the suite.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationMuteArchiveFlowIT {

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
    private MessageService messageService;
    @Autowired
    private MockFcmGateway mockFcmGateway;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // non-member probe for the alice↔bob room
    private TestUser dave;
    private TestUser erin;

    @BeforeAll
    void registerUsers() throws Exception {
        String like = "phone LIKE '+8490%' AND phone NOT LIKE '+849000%'";
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
        String tag = "ITMA" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser("Zz" + tag + " Carol");
        dave = registerUser(tag + " Dave");
        erin = registerUser(tag + " Erin");
    }

    @AfterAll
    void removeUsers() {
        String like = "phone LIKE '+8490%' AND phone NOT LIKE '+849000%'";
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8490" + (1 + random.nextInt(9)) + String.format("%06d", random.nextInt(1_000_000));
    }

    private TestUser registerUser(String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", randomPhone(), "password", "secret123",
                                        "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                UUID.fromString(body.get("user").get("id").asText()),
                null, displayName, body.get("accessToken").asText());
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

    private ResultActions putJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(put(path)
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

    /** Opens a direct chat and gives it one message so it sorts into the inbox. */
    private UUID conversationWithMessage(TestUser owner, TestUser partner, String content)
            throws Exception {
        UUID conversationId = openDirectConversation(owner, partner);
        sendRest(conversationId, owner, content);
        return conversationId;
    }

    private List<JsonNode> inbox(String accessToken, String query) throws Exception {
        MvcResult result = getJson("/api/v1/conversations" + query, accessToken)
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> entries = new ArrayList<>();
        objectMapper.readTree(result.getResponse().getContentAsString())
                .forEach(entries::add);
        return entries;
    }

    private JsonNode entry(List<JsonNode> entries, UUID conversationId) {
        for (JsonNode node : entries) {
            if (conversationId.toString().equals(node.get("id").asText())) {
                return node;
            }
        }
        return null;
    }

    // ================================================================ REST flow

    @Test
    void memberMutesUnmutesArchivesAndUnarchivesAndNonMemberIsRejected() throws Exception {
        UUID conversationId = conversationWithMessage(alice, bob, "mute me");

        putJson("/api/v1/conversations/" + conversationId + "/mute", bob.accessToken(),
                Map.of("mutedUntil", "2026-12-09T10:00:00Z"))
                .andExpect(status().isNoContent());
        JsonNode bobInbox = entry(inbox(bob.accessToken(), ""), conversationId);
        assertThat(bobInbox).isNotNull();
        assertThat(bobInbox.get("muted").asBoolean()).isTrue();
        assertThat(bobInbox.get("mutedUntil").asText()).isEqualTo("2026-12-09T10:00:00Z");
        assertThat(bobInbox.get("archived").asBoolean()).isFalse();

        // Per-member: alice sees neither flag.
        JsonNode aliceInbox = entry(inbox(alice.accessToken(), ""), conversationId);
        assertThat(aliceInbox.get("muted").asBoolean()).isFalse();
        assertThat(aliceInbox.get("archived").asBoolean()).isFalse();

        putJson("/api/v1/conversations/" + conversationId + "/archive", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        assertThat(entry(inbox(bob.accessToken(), "?archived=true"), conversationId))
                .isNotNull();
        assertThat(entry(inbox(bob.accessToken(), ""), conversationId)).isNull();

        deleteJson("/api/v1/conversations/" + conversationId + "/archive", bob.accessToken())
                .andExpect(status().isNoContent());
        deleteJson("/api/v1/conversations/" + conversationId + "/mute", bob.accessToken())
                .andExpect(status().isNoContent());
        JsonNode cleared = entry(inbox(bob.accessToken(), ""), conversationId);
        assertThat(cleared.get("muted").asBoolean()).isFalse();
        assertThat(cleared.has("mutedUntil")).isFalse();
        assertThat(cleared.get("archived").asBoolean()).isFalse();

        // Non-member: 403 on every verb.
        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        deleteJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        putJson("/api/v1/conversations/" + conversationId + "/archive", carol.accessToken(), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        deleteJson("/api/v1/conversations/" + conversationId + "/archive", carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void unknownConversationIs404AndValidationIsFailClosed() throws Exception {
        UUID missingId = UUID.randomUUID();
        putJson("/api/v1/conversations/" + missingId + "/mute", alice.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
        putJson("/api/v1/conversations/" + missingId + "/archive", alice.accessToken(), Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));

        UUID conversationId = conversationWithMessage(alice, carol, "validate me");

        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("mutedUntil", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("mutedUntil", "2999-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("mutedUntil", "2026-12-09T10:00:00Z", "forever", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // The rejected calls changed nothing.
        JsonNode carolInbox = entry(inbox(carol.accessToken(), ""), conversationId);
        assertThat(carolInbox.get("muted").asBoolean()).isFalse();
    }

    @Test
    void repeatedIdenticalMutationsAreIdempotent() throws Exception {
        UUID conversationId = conversationWithMessage(bob, dave, "idempotent");

        for (int i = 0; i < 2; i++) {
            putJson("/api/v1/conversations/" + conversationId + "/mute", dave.accessToken(),
                    Map.of("forever", true))
                    .andExpect(status().isNoContent());
            putJson("/api/v1/conversations/" + conversationId + "/archive", dave.accessToken(), Map.of())
                    .andExpect(status().isNoContent());
            deleteJson("/api/v1/conversations/" + conversationId + "/mute", dave.accessToken())
                    .andExpect(status().isNoContent());
            deleteJson("/api/v1/conversations/" + conversationId + "/archive", dave.accessToken())
                    .andExpect(status().isNoContent());
        }

        JsonNode daveInbox = entry(inbox(dave.accessToken(), ""), conversationId);
        assertThat(daveInbox.get("muted").asBoolean()).isFalse();
        assertThat(daveInbox.get("archived").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, dave.id())).isEqualTo(1);
    }

    @Test
    void inboxFilterAndPagingHonourArchiveState() throws Exception {
        UUID archivedA = conversationWithMessage(bob, carol, "archived a");
        UUID archivedB = conversationWithMessage(bob, erin, "archived b");
        UUID live = conversationWithMessage(bob, dave, "live");

        putJson("/api/v1/conversations/" + archivedA + "/archive", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());
        putJson("/api/v1/conversations/" + archivedB + "/archive", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        assertThat(entry(inbox(bob.accessToken(), ""), archivedA)).isNull();
        assertThat(entry(inbox(bob.accessToken(), ""), archivedB)).isNull();
        assertThat(entry(inbox(bob.accessToken(), ""), live)).isNotNull();

        assertThat(entry(inbox(bob.accessToken(), "?archived=false"), archivedA)).isNull();
        assertThat(entry(inbox(bob.accessToken(), "?archived=false"), live)).isNotNull();

        List<JsonNode> archivedOnly = inbox(bob.accessToken(), "?archived=true");
        assertThat(entry(archivedOnly, archivedA)).isNotNull();
        assertThat(entry(archivedOnly, archivedB)).isNotNull();
        assertThat(entry(archivedOnly, live)).isNull();

        // Keyset paging over the archived-only view: page 1 returns one row and a
        // cursor, page 2 returns the other, and `live` never appears.
        MvcResult pageOne = getJson("/api/v1/conversations?archived=true&limit=1", bob.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstPage = objectMapper.readTree(pageOne.getResponse().getContentAsString());
        assertThat(firstPage).hasSize(1);
        String cursor = pageOne.getResponse().getHeader("X-Next-Cursor");
        assertThat(cursor).isNotBlank();
        UUID firstId = UUID.fromString(firstPage.get(0).get("id").asText());
        UUID expectedOther = firstId.equals(archivedA) ? archivedB : archivedA;
        assertThat(firstId).isIn(archivedA, archivedB);

        MvcResult pageTwo = getJson(
                "/api/v1/conversations?archived=true&limit=1&cursor=" + cursor, bob.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondPage = objectMapper.readTree(pageTwo.getResponse().getContentAsString());
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).get("id").asText()).isEqualTo(expectedOther.toString());
        assertThat(pageTwo.getResponse().getHeader("X-Next-Cursor")).isNull();
    }

    @Test
    void deltaSyncCarriesTheCallerMuteAndArchiveFlags() throws Exception {
        UUID conversationId = conversationWithMessage(alice, erin, "delta flags");

        postJson("/api/v1/sync", erin.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].muted").value(false))
                .andExpect(jsonPath("$.deltas[0].mutedUntil").doesNotExist())
                .andExpect(jsonPath("$.deltas[0].archived").value(false));

        putJson("/api/v1/conversations/" + conversationId + "/mute", erin.accessToken(),
                Map.of("mutedUntil", "2026-12-09T10:00:00Z"))
                .andExpect(status().isNoContent());
        putJson("/api/v1/conversations/" + conversationId + "/archive", erin.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        postJson("/api/v1/sync", erin.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].muted").value(true))
                .andExpect(jsonPath("$.deltas[0].mutedUntil").value("2026-12-09T10:00:00Z"))
                .andExpect(jsonPath("$.deltas[0].archived").value(true));

        // The other member's delta is unaffected.
        postJson("/api/v1/sync", alice.accessToken(),
                Map.of("watermarks", List.of(
                        Map.of("conversationId", conversationId.toString(), "lastSeq", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deltas[0].muted").value(false))
                .andExpect(jsonPath("$.deltas[0].archived").value(false));
    }

    @Test
    void pushIsSkippedForMutedConversationAndSystemNotices() throws Exception {
        UUID conversationId = createGroup(alice, bob, dave);
        String bobToken = "it-sys-bob-" + UUID.randomUUID();
        String daveToken = "it-sys-dave-" + UUID.randomUUID();
        registerDeviceToken(bob, bobToken);
        registerDeviceToken(dave, daveToken);

        putJson("/api/v1/conversations/" + conversationId + "/mute", dave.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        sendRest(conversationId, alice, "ordinary");
        assertThat(sentTokens()).contains(bobToken).doesNotContain(daveToken);

        // A title change posts a SYSTEM message; the muted member is skipped.
        mockFcmGateway.clear();
        patchGroupTitle(alice, conversationId, "Renamed " + UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(sentTokens()).contains(bobToken).doesNotContain(daveToken);

        // A member-add SYSTEM message is skipped for the muted member too.
        mockFcmGateway.clear();
        addMember(alice, conversationId, erin).andExpect(status().isNoContent());
        assertThat(sentTokens()).contains(bobToken).doesNotContain(daveToken);

        // Unmuting restores the ordinary push; the SYSTEM notices stay consistent.
        mockFcmGateway.clear();
        deleteJson("/api/v1/conversations/" + conversationId + "/mute", dave.accessToken())
                .andExpect(status().isNoContent());
        sendRest(conversationId, alice, "after unmute");
        assertThat(sentTokens()).contains(bobToken, daveToken);
    }

    @Test
    void removedMutedMemberStillReceivesRemovalNotice() throws Exception {
        UUID conversationId = createGroup(alice, bob, carol);
        String bobToken = "it-removed-bob-" + UUID.randomUUID();
        String carolToken = "it-removed-carol-" + UUID.randomUUID();
        registerDeviceToken(bob, bobToken);
        registerDeviceToken(carol, carolToken);

        putJson("/api/v1/conversations/" + conversationId + "/mute", bob.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNoContent());
        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        deleteJson("/api/v1/conversations/" + conversationId + "/members/" + bob.id(),
                alice.accessToken())
                .andExpect(status().isNoContent());

        // Bob is the subject of the removal, so he is told even though he muted
        // and is no longer a member. Carol is muted and not the subject.
        assertThat(sentTokens()).contains(bobToken).doesNotContain(carolToken);
    }

    @Test
    void muteExpiryBoundaryStillPushesSystemNotice() throws Exception {
        UUID conversationId = createGroup(alice, bob);
        String bobToken = "it-expiry-bob-" + UUID.randomUUID();
        registerDeviceToken(bob, bobToken);

        // A past muted_until is not an active mute (lazy expiry, no cleanup).
        jdbcTemplate.update("UPDATE conversation_members SET muted = true, muted_until = ? "
                        + "WHERE conversation_id = ? AND user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), conversationId, bob.id());

        mockFcmGateway.clear();
        patchGroupTitle(alice, conversationId, "Expiry " + UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(sentTokens()).contains(bobToken);

        // A future muted_until mutes again and suppresses the SYSTEM notice.
        putJson("/api/v1/conversations/" + conversationId + "/mute", bob.accessToken(),
                Map.of("mutedUntil", Instant.now().plusSeconds(3600).toString()))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        patchGroupTitle(alice, conversationId, "Expiry two " + UUID.randomUUID())
                .andExpect(status().isOk());
        assertThat(sentTokens()).doesNotContain(bobToken);
    }

    @Test
    void memberLeftSystemNoticeRespectsMute() throws Exception {
        UUID conversationId = createGroup(alice, bob, carol);
        String aliceToken = "it-left-alice-" + UUID.randomUUID();
        String carolToken = "it-left-carol-" + UUID.randomUUID();
        registerDeviceToken(alice, aliceToken);
        registerDeviceToken(carol, carolToken);

        putJson("/api/v1/conversations/" + conversationId + "/mute", carol.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        postJson("/api/v1/conversations/" + conversationId + "/leave", bob.accessToken(), Map.of())
                .andExpect(status().isNoContent());

        assertThat(sentTokens()).contains(aliceToken).doesNotContain(carolToken);
    }

    @Test
    void roleChangeSubjectBypassForMutedMember() throws Exception {
        UUID conversationId = createGroup(alice, bob);
        String bobToken = "it-role-bob-" + UUID.randomUUID();
        registerDeviceToken(bob, bobToken);

        putJson("/api/v1/conversations/" + conversationId + "/mute", bob.accessToken(),
                Map.of("forever", true))
                .andExpect(status().isNoContent());

        mockFcmGateway.clear();
        // No REST role-change endpoint exists yet; post the SYSTEM event with its
        // subject directly to exercise the push gate for a demotion notice.
        messageService.postSystemMessage(alice.id(), conversationId,
                "Alice changed Bob's role", bob.id());

        assertThat(sentTokens()).contains(bobToken);
    }

    // ------------------------------------------------------------ push helpers

    private UUID createGroup(TestUser admin, TestUser... members) throws Exception {
        List<String> memberIds = Arrays.stream(members)
                .map(member -> member.id().toString()).toList();
        MvcResult result = postJson("/api/v1/conversations/group", admin.accessToken(),
                Map.of("title", "IT group " + UUID.randomUUID(), "memberIds", memberIds))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private void registerDeviceToken(TestUser user, String token) throws Exception {
        postJson("/api/v1/users/device-token", user.accessToken(),
                Map.of("token", token, "platform", "ANDROID", "deviceLabel", "IT phone"))
                .andExpect(status().isOk());
    }

    private ResultActions patchGroupTitle(TestUser admin, UUID conversationId, String title)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/conversations/" + conversationId)
                .header("Authorization", "Bearer " + admin.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", title))));
    }

    private ResultActions addMember(TestUser admin, UUID conversationId, TestUser member)
            throws Exception {
        return postJson("/api/v1/conversations/" + conversationId + "/members",
                admin.accessToken(), Map.of("userIds", List.of(member.id().toString())));
    }

    private List<String> sentTokens() {
        return mockFcmGateway.getSentPushes().stream()
                .map(MockFcmGateway.SentPush::token)
                .toList();
    }
}
