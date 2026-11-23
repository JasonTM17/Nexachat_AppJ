package com.nexachat.scheduled;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST coverage for the author-scoped scheduled-message endpoints.
 *
 * <p>Users use the range assigned to this suite by {@code ItPhoneRanges} so it
 * never touches another IT's fixtures. The dispatcher is disabled for the
 * class ({@code nexachat.scheduled.enabled=false} in the it profile); its
 * behaviour is covered by {@link ScheduledMessageDispatchIT}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledMessageFlowIT {

    private static final String PHONE_PREFIX = ItPhoneRanges.prefixFor(ScheduledMessageFlowIT.class);

    @DynamicPropertySource
    static void scheduledProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.scheduled.enabled", () -> "false");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private TestUser dave;

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, ScheduledMessageFlowIT.class);
        String tag = "SMF" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
    }

    @BeforeEach
    void clearScheduled() {
        jdbcTemplate.update(
                "DELETE FROM scheduled_messages WHERE sender_id IN"
                        + " (SELECT id FROM users WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
    }

    // -------------------------------------------------------------- create

    @Test
    void createReturns201AndEchoesTheClampedSendAt() throws Exception {
        UUID conversationId = createGroup(alice, "Clamp", bob);

        JsonNode soon = createScheduled(bob, conversationId, "too soon",
                        Instant.now().minus(5, ChronoUnit.MINUTES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andReturnJson();
        Instant echoedSoon = Instant.parse(soon.get("sendAt").asText());
        // The floor is now+60s; a past value must be clamped, not rejected.
        assertThat(echoedSoon).isAfter(Instant.now().plus(50, ChronoUnit.SECONDS));
        assertThat(echoedSoon).isBefore(Instant.now().plus(70, ChronoUnit.SECONDS));

        JsonNode far = createScheduled(bob, conversationId, "too far",
                        Instant.now().plus(400, ChronoUnit.DAYS))
                .andExpect(status().isCreated())
                .andReturnJson();
        Instant echoedFar = Instant.parse(far.get("sendAt").asText());
        assertThat(echoedFar).isBefore(Instant.now().plus(366, ChronoUnit.DAYS));
        assertThat(echoedFar).isAfter(Instant.now().plus(364, ChronoUnit.DAYS));

        Instant inside = Instant.now().plus(2, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS);
        JsonNode inRange = createScheduled(bob, conversationId, "in range", inside)
                .andExpect(status().isCreated())
                .andReturnJson();
        assertThat(Instant.parse(inRange.get("sendAt").asText()))
                .isEqualTo(inside);
        assertThat(inRange.get("body").asText()).isEqualTo("in range");
        assertThat(inRange.get("failureCode").isNull()).isTrue();
        assertThat(inRange.get("sentMessageId").isNull()).isTrue();
    }

    @Test
    void replayingAPendingKeyReturns200WithTheStoredRow() throws Exception {
        UUID conversationId = createGroup(alice, "Replay", bob);
        UUID key = UUID.randomUUID();
        Instant sendAt = Instant.now().plus(2, ChronoUnit.HOURS);

        JsonNode first = createScheduled(bob, conversationId, "first", sendAt, key)
                .andExpect(status().isCreated())
                .andReturnJson();
        JsonNode replay = createScheduled(bob, conversationId, "changed body", sendAt, key)
                .andExpect(status().isOk())
                .andReturnJson();

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("body").asText()).isEqualTo("first");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_messages WHERE conversation_id = ?",
                Integer.class, conversationId)).isEqualTo(1);
    }

    @Test
    void replayingATerminalKeyIsRejectedWithTheStatus() throws Exception {
        UUID conversationId = createGroup(alice, "Terminal replay", bob);
        UUID key = UUID.randomUUID();
        UUID id = createScheduled(bob, conversationId, "doomed",
                        Instant.now().plus(2, ChronoUnit.HOURS), key)
                .andExpect(status().isCreated())
                .returnId();
        deleteJson(conversationId, id, bob).andExpect(status().isNoContent());

        createScheduled(bob, conversationId, "doomed",
                        Instant.now().plus(2, ChronoUnit.HOURS), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCHEDULED_INVALID"))
                .andExpect(jsonPath("$.error.details.status").value("CANCELED"));
    }

    @Test
    void reusingAKeyAcrossConversationsIsAValidationError() throws Exception {
        UUID first = createGroup(alice, "First room", bob);
        UUID second = createGroup(alice, "Second room", bob);
        UUID key = UUID.randomUUID();

        createScheduled(bob, first, "original",
                        Instant.now().plus(2, ChronoUnit.HOURS), key)
                .andExpect(status().isCreated());
        createScheduled(bob, second, "different room",
                        Instant.now().plus(2, ChronoUnit.HOURS), key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theFiftyPendingCeilingIsPerSenderPerConversation() throws Exception {
        UUID conversationId = createGroup(alice, "Ceiling", bob, carol);
        seedPending(conversationId, bob, 50);
        // carol's rows do not count against bob.
        seedPending(conversationId, carol, 50);

        createScheduled(bob, conversationId, "over the limit",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCHEDULED_LIMIT"))
                .andExpect(jsonPath("$.error.details.limit").value(50));

        // A terminal row frees its slot.
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET status = 'CANCELED', body = '', claimed_at = NULL, claim_token = NULL
                WHERE conversation_id = ? AND sender_id = ? AND status = 'PENDING'
                """, conversationId, bob.id());
        createScheduled(bob, conversationId, "fits now",
                        Instant.now().plus(3, ChronoUnit.HOURS))
                .andExpect(status().isCreated());

        // And the ceiling applies to carol independently of bob's rows.
        createScheduled(carol, conversationId, "carol over",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SCHEDULED_LIMIT"));
    }

    @Test
    void schedulingIntoAnE2eeConversationIsRejectedLikeASend() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        jdbcTemplate.update(
                "UPDATE conversations SET e2ee_enabled = true WHERE id = ?", conversationId);

        createScheduled(alice, conversationId, "cannot schedule",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E2EE_REQUIRED"));
    }

    // ------------------------------------------------------------------ list

    @Test
    void listShowsOnlyTheCallersRowsOrderedBySendAt() throws Exception {
        UUID conversationId = createGroup(alice, "Private board", bob, carol);
        Instant later = Instant.now().plus(3, ChronoUnit.HOURS);
        Instant sooner = Instant.now().plus(1, ChronoUnit.HOURS);

        createScheduled(bob, conversationId, "bob-later", later).andExpect(status().isCreated());
        createScheduled(bob, conversationId, "bob-sooner", sooner).andExpect(status().isCreated());
        createScheduled(carol, conversationId, "carol-row", later).andExpect(status().isCreated());

        JsonNode bobList = getJson(conversationId, bob)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(bodies(bobList)).containsExactly("bob-sooner", "bob-later");

        JsonNode carolList = getJson(conversationId, carol)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(bodies(carolList)).containsExactly("carol-row");

        JsonNode aliceList = getJson(conversationId, alice)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(aliceList).isEmpty();

        // Non-members learn nothing.
        getJson(conversationId, dave)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        getJson(UUID.randomUUID(), bob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancelRemovesTheRowFromTheLiveList() throws Exception {
        UUID conversationId = createGroup(alice, "Cancel", bob);
        UUID id = createScheduled(bob, conversationId, "cancel me",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();

        deleteJson(conversationId, id, bob).andExpect(status().isNoContent());
        assertThat(getJson(conversationId, bob).andReturnJson()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM scheduled_messages WHERE id = ?",
                String.class, id)).isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body FROM scheduled_messages WHERE id = ?",
                String.class, id)).isEqualTo("");

        // A terminal row cannot be canceled again.
        deleteJson(conversationId, id, bob)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPATCHING"))
                .andExpect(jsonPath("$.error.details.status").value("CANCELED"));
    }

    @Test
    void editUpdatesBodyAndSendAtAndEchoesTheRow() throws Exception {
        UUID conversationId = createGroup(alice, "Edit", bob);
        UUID id = createScheduled(bob, conversationId, "before",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();
        Instant moved = Instant.now().plus(5, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS);

        JsonNode echo = patchJson(conversationId, id, bob,
                        Map.of("body", "after", "sendAt", moved.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturnJson();
        assertThat(echo.get("body").asText()).isEqualTo("after");
        assertThat(Instant.parse(echo.get("sendAt").asText())).isEqualTo(moved);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body FROM scheduled_messages WHERE id = ?",
                String.class, id)).isEqualTo("after");
    }

    @Test
    void editHonorsTheClaimGuardAndOwnership() throws Exception {
        UUID conversationId = createGroup(alice, "Edit guard", bob);
        UUID id = createScheduled(bob, conversationId, "claimed",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();

        // A live claim blocks the edit exactly like it blocks the cancel.
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now(), claim_token = ?
                WHERE id = ?
                """, UUID.randomUUID(), id);
        patchJson(conversationId, id, bob, Map.of("body", "hijack"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPATCHING"))
                .andExpect(jsonPath("$.error.details.status").value("PENDING"));

        // An expired lease releases the guard — and the edit must release the
        // stale claim itself, or a stalled dispatcher's token stays valid and
        // the wake-up scan spins on the expired claimed_at instant.
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now() - interval '10 minutes'
                WHERE id = ?
                """, id);
        patchJson(conversationId, id, bob, Map.of("body", "after"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM scheduled_messages"
                        + " WHERE id = ? AND claimed_at IS NULL AND claim_token IS NULL",
                Integer.class, id)).isEqualTo(1);

        // Another member cannot edit bob's row; the terminal row is a conflict.
        patchJson(conversationId, id, alice, Map.of("body", "alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        deleteJson(conversationId, id, bob).andExpect(status().isNoContent());
        patchJson(conversationId, id, bob, Map.of("body", "again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPATCHING"))
                .andExpect(jsonPath("$.error.details.status").value("CANCELED"));
    }

    @Test
    void aClaimedRowIsNotCancelableUntilTheLeaseExpires() throws Exception {
        UUID conversationId = createGroup(alice, "Dispatching", bob);
        UUID id = createScheduled(bob, conversationId, "claimed",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();

        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now(), claim_token = ?
                WHERE id = ?
                """, UUID.randomUUID(), id);
        deleteJson(conversationId, id, bob)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPATCHING"))
                .andExpect(jsonPath("$.error.details.status").value("PENDING"));

        // An abandoned claim whose lease already expired does not block cancel.
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now() - interval '10 minutes'
                WHERE id = ?
                """, id);
        deleteJson(conversationId, id, bob).andExpect(status().isNoContent());
    }

    @Test
    void cancelIsAuthorScopedAndConversationScoped() throws Exception {
        UUID conversationId = createGroup(alice, "Owned", bob);
        UUID otherConversation = createGroup(alice, "Other", bob);
        UUID id = createScheduled(bob, conversationId, "bob's",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();

        // Another member cannot cancel bob's row.
        deleteJson(conversationId, id, alice)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // Nor can bob cancel it through a different conversation's route.
        deleteJson(otherConversation, id, bob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        // A non-member gets the membership failure before the row check.
        deleteJson(conversationId, id, dave)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        // Unknown id under a real conversation is 404.
        deleteJson(conversationId, UUID.randomUUID(), bob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        deleteJson(conversationId, id, bob).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------ validation

    @Test
    void validationRejectsBadInput() throws Exception {
        UUID conversationId = createGroup(alice, "Validate", bob);
        Instant sendAt = Instant.now().plus(2, ChronoUnit.HOURS);

        createScheduledRaw(bob, conversationId, Map.of(
                        "clientMessageId", UUID.randomUUID().toString(),
                        "body", "   ",
                        "sendAt", sendAt.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createScheduledRaw(bob, conversationId, Map.of(
                        "clientMessageId", UUID.randomUUID().toString(),
                        "body", "has a \u0000 byte",
                        "sendAt", sendAt.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createScheduledRaw(bob, conversationId, Map.of(
                        "clientMessageId", UUID.randomUUID().toString(),
                        "body", "x".repeat(10_001),
                        "sendAt", sendAt.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createScheduledRaw(bob, conversationId, Map.of(
                        "clientMessageId", UUID.randomUUID().toString(),
                        "sendAt", sendAt.toString()))
                .andExpect(status().isBadRequest());
        createScheduledRaw(bob, conversationId, Map.of(
                        "body", "no key",
                        "sendAt", sendAt.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMemberCannotSchedule() throws Exception {
        UUID conversationId = createGroup(alice, "Closed", bob);
        createScheduled(dave, conversationId, "intruder",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_messages WHERE conversation_id = ?",
                Integer.class, conversationId)).isZero();
    }

    // -------------------------------------------------------- account delete

    @Test
    void deletingTheAccountCancelsItsPendingRows() throws Exception {
        // A dedicated account: deletion tombstones the user, so it must not be
        // one of the shared fixtures or every later group build would 404.
        TestUser ghost = registerUser("SMF Ghost " + System.nanoTime());
        UUID conversationId = createGroup(alice, "Ghost", ghost);
        UUID pending = createScheduled(ghost, conversationId, "never sent",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();
        UUID keepAlive = createScheduled(alice, conversationId, "alive",
                        Instant.now().plus(2, ChronoUnit.HOURS))
                .andExpect(status().isCreated())
                .returnId();

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer " + ghost.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "secret123"))))
                .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_code, body FROM scheduled_messages WHERE id = ?",
                pending);
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("failure_code")).isEqualTo("ACCOUNT_DELETED");
        assertThat(row.get("body")).isEqualTo("");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM scheduled_messages WHERE id = ?",
                String.class, keepAlive)).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- helpers

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(ScheduledMessageFlowIT.class);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone,
                                "password", "secret123",
                                "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID createGroup(TestUser admin, String title, TestUser... members) throws Exception {
        List<String> memberIds = new ArrayList<>();
        for (TestUser member : members) {
            memberIds.add(member.id().toString());
        }
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", "Bearer " + admin.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title,
                                "memberIds", memberIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID openDirect(TestUser one, TestUser other) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header("Authorization", "Bearer " + one.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userId", other.id().toString()))))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private ScheduledResult getJson(UUID conversationId, TestUser user) throws Exception {
        return new ScheduledResult(mockMvc.perform(get(path(conversationId))
                .header("Authorization", "Bearer " + user.accessToken())));
    }

    private ScheduledResult createScheduled(TestUser actor, UUID conversationId, String body,
                                            Instant sendAt) throws Exception {
        return createScheduled(actor, conversationId, body, sendAt, UUID.randomUUID());
    }

    private ScheduledResult createScheduled(TestUser actor, UUID conversationId, String body,
                                            Instant sendAt, UUID key) throws Exception {
        return createScheduledRaw(actor, conversationId, Map.of(
                "clientMessageId", key.toString(),
                "body", body,
                "sendAt", sendAt.toString()));
    }

    private ScheduledResult createScheduledRaw(TestUser actor, UUID conversationId,
                                               Object payload) throws Exception {
        return new ScheduledResult(mockMvc.perform(post(path(conversationId))
                .header("Authorization", "Bearer " + actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))));
    }

    private ResultActions deleteJson(UUID conversationId, UUID scheduledId, TestUser actor)
            throws Exception {
        return mockMvc.perform(delete(path(conversationId) + "/" + scheduledId)
                .header("Authorization", "Bearer " + actor.accessToken()));
    }

    private ScheduledResult patchJson(UUID conversationId, UUID scheduledId, TestUser actor,
                                      Object payload) throws Exception {
        return new ScheduledResult(mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(path(conversationId) + "/" + scheduledId)
                        .header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload))));
    }

    private static String path(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/scheduled-messages";
    }

    private static List<String> bodies(JsonNode list) {
        List<String> bodies = new ArrayList<>();
        list.forEach(item -> bodies.add(item.get("body").asText()));
        return bodies;
    }

    private void seedPending(UUID conversationId, TestUser sender, int count) {
        Instant anchor = Instant.now().plus(2, ChronoUnit.HOURS);
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO scheduled_messages
                        (conversation_id, sender_id, client_message_id, body, send_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    conversationId,
                    sender.id(),
                    UUID.randomUUID(),
                    "Seed " + i,
                    OffsetDateTime.ofInstant(anchor.plusSeconds(i), ZoneOffset.UTC));
        }
    }

    /** Small wrapper so a test can set expectations before reading the body. */
    private final class ScheduledResult {
        private final ResultActions actions;

        private ScheduledResult(ResultActions actions) {
            this.actions = actions;
        }

        private ScheduledResult andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnJson() throws Exception {
            return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }

        private UUID returnId() throws Exception {
            return UUID.fromString(andReturnJson().get("id").asText());
        }
    }
}
