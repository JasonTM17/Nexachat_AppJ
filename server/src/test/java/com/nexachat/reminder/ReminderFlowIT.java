package com.nexachat.reminder;

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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CRUD and authorization coverage for the conversation reminder board.
 *
 * <p>Users use the range assigned to this suite by {@code ItPhoneRanges} so it
 * never touches another IT's fixtures. The background scheduler is disabled for the class;
 * scheduler behaviour is covered by {@link ReminderSchedulerIT}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReminderFlowIT {

    private static final String PHONE_PREFIX = ItPhoneRanges.prefixFor(ReminderFlowIT.class);

    @DynamicPropertySource
    static void reminderProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.reminders.enabled", () -> "false");
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
        ItPhoneRanges.clean(jdbcTemplate, ReminderFlowIT.class);
        String tag = "RMF" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
    }

    @BeforeEach
    void clearReminders() {
        jdbcTemplate.update(
                "DELETE FROM conversation_reminders WHERE created_by IN"
                        + " (SELECT id FROM users WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    void membersCreateAndListUpcomingRemindersInOrder() throws Exception {
        UUID conversationId = createGroup(alice, "Reminder board", bob, carol);

        Instant threeHours = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant oneHour = threeHours.minus(2, ChronoUnit.HOURS);
        Instant twoHours = threeHours.minus(1, ChronoUnit.HOURS);

        JsonNode third = createReminder(bob, conversationId, "Third", threeHours, "NONE", null)
                .andExpect(status().isCreated())
                .andReturnJson();
        createReminder(bob, conversationId, "First", oneHour, "NONE", null)
                .andExpect(status().isCreated());
        createReminder(bob, conversationId, "Second", twoHours, "DAILY", null)
                .andExpect(status().isCreated());

        assertThat(third.get("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(third.get("createdBy").asText()).isEqualTo(bob.id().toString());
        assertThat(third.get("createdByName").asText()).isNotBlank();
        assertThat(third.get("repeat").asText()).isEqualTo("NONE");
        assertThat(third.get("canEdit").asBoolean()).isTrue();

        JsonNode bobList = getJson(conversationId, bob).andExpect(status().isOk()).andReturnJson();
        assertThat(titles(bobList)).containsExactly("First", "Second", "Third");
        assertThat(bobList.get(0).get("canEdit").asBoolean()).isTrue();

        JsonNode aliceList = getJson(conversationId, alice).andExpect(status().isOk()).andReturnJson();
        assertThat(titles(aliceList)).containsExactly("First", "Second", "Third");
        // alice is a group admin, so she may edit every reminder.
        assertThat(aliceList.get(0).get("canEdit").asBoolean()).isTrue();

        JsonNode carolList = getJson(conversationId, carol).andExpect(status().isOk()).andReturnJson();
        assertThat(titles(carolList)).containsExactly("First", "Second", "Third");
        assertThat(carolList.get(0).get("canEdit").asBoolean()).isFalse();

        getJson(conversationId, dave)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        getJson(UUID.randomUUID(), bob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void creatorOrGroupAdminCanUpdateAndDelete() throws Exception {
        UUID conversationId = createGroup(alice, "Editors", bob, carol);
        UUID reminderId = createReminder(bob, conversationId, "Original",
                Instant.now().plus(2, ChronoUnit.HOURS), "NONE", null)
                .andExpect(status().isCreated())
                .returnId();

        // A plain member who is not the creator is denied.
        patchJson(conversationId, reminderId, carol, Map.of("title", "Carol edit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        deleteJson(conversationId, reminderId, carol)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        // The creator may edit.
        patchJson(conversationId, reminderId, bob, Map.of("title", "Bob edit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Bob edit"))
                .andExpect(jsonPath("$.canEdit").value(true));

        // A group admin may edit even though another member created it.
        Instant moved = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        patchJson(conversationId, reminderId, alice,
                Map.of("title", "Admin edit", "remindAt", moved.toString(), "repeat", "WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Admin edit"))
                .andExpect(jsonPath("$.remindAt").value(moved.toString()))
                .andExpect(jsonPath("$.repeat").value("WEEKLY"));

        // Non-members fail closed on every write.
        patchJson(conversationId, reminderId, dave, Map.of("title", "Dave edit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        deleteJson(conversationId, reminderId, dave)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // A group admin may delete someone else's reminder.
        deleteJson(conversationId, reminderId, alice).andExpect(status().isNoContent());
        assertThat(getJson(conversationId, bob).andReturnJson()).isEmpty();
        deleteJson(conversationId, reminderId, alice)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void directConversationOnlyTheCreatorCanEdit() throws Exception {
        UUID conversationId = openDirect(alice, bob);
        UUID reminderId = createReminder(alice, conversationId, "Direct reminder",
                Instant.now().plus(90, ChronoUnit.MINUTES), "NONE", null)
                .andExpect(status().isCreated())
                .returnId();

        // The other direct participant is not an admin and cannot edit.
        patchJson(conversationId, reminderId, bob, Map.of("title", "Not mine"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        deleteJson(conversationId, reminderId, bob)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Both participants can read the shared board.
        assertThat(getJson(conversationId, bob).andExpect(status().isOk())
                .andReturnJson()).hasSize(1);

        patchJson(conversationId, reminderId, alice, Map.of("title", "Mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Mine"))
                .andExpect(jsonPath("$.canEdit").value(true));
    }

    @Test
    void changingTheScheduleInvalidatesAnInFlightClaim() throws Exception {
        UUID conversationId = createGroup(alice, "Claim edit", bob);
        Instant original = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        UUID reminderId = createReminder(bob, conversationId, "Claimed", original, "NONE", null)
                .andExpect(status().isCreated())
                .returnId();

        jdbcTemplate.update("""
                UPDATE conversation_reminders
                SET claimed_at = now(), claim_token = ?
                WHERE id = ?
                """, UUID.randomUUID(), reminderId);

        Instant moved = original.plus(1, ChronoUnit.HOURS);
        patchJson(conversationId, reminderId, bob, Map.of("remindAt", moved.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindAt").value(moved.toString()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_token FROM conversation_reminders WHERE id = ?",
                UUID.class, reminderId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claimed_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remind_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId).toInstant()).isEqualTo(moved);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT anchor_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId).toInstant()).isEqualTo(moved);
    }

    // ------------------------------------------------------------ validation

    @Test
    void validationRejectsBadInputAndUnknownTargets() throws Exception {
        UUID conversationId = createGroup(alice, "Validation", bob);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        createReminder(bob, conversationId, "   ", future, "NONE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createReminder(bob, conversationId, "Past", Instant.now().minusSeconds(60), "NONE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createReminder(bob, conversationId, "Too far", Instant.now().plus(366, ChronoUnit.DAYS),
                "NONE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        createReminder(bob, conversationId, "Bad repeat", future, "YEARLY", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        UUID reminderId = createReminder(bob, conversationId, "Keep", future, "NONE", null)
                .andExpect(status().isCreated())
                .returnId();

        patchJson(conversationId, reminderId, bob, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        patchJson(conversationId, reminderId, bob, Map.of("title", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        patchJson(conversationId, reminderId, bob, Map.of("repeat", "YEARLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        patchJson(conversationId, UUID.randomUUID(), bob, Map.of("title", "Ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        deleteJson(conversationId, UUID.randomUUID(), bob)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        UUID otherConversation = createGroup(alice, "Other", bob);
        patchJson(otherConversation, reminderId, bob, Map.of("title", "Wrong room"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void nonMemberCannotCreateAReminder() throws Exception {
        UUID conversationId = createGroup(alice, "Closed", bob);
        createReminder(dave, conversationId, "Intruder",
                Instant.now().plus(1, ChronoUnit.HOURS), "NONE", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_reminders WHERE conversation_id = ?",
                Integer.class, conversationId)).isZero();
    }

    // ------------------------------------------------------------------ limit

    @Test
    void theFiftyActiveReminderCeilingExcludesFiredRows() throws Exception {
        UUID conversationId = createGroup(alice, "Ceiling", bob);
        seedReminders(conversationId, alice, 49, false);
        seedReminders(conversationId, alice, 1, true);

        UUID created = createReminder(bob, conversationId, "Fiftieth",
                Instant.now().plus(2, ChronoUnit.HOURS), "NONE", null)
                .andExpect(status().isCreated())
                .returnId();

        createReminder(bob, conversationId, "Over the limit",
                Instant.now().plus(3, ChronoUnit.HOURS), "NONE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.limit").value(50));

        // Completing a one-off row frees a slot.
        jdbcTemplate.update(
                "UPDATE conversation_reminders SET fired_at = now() WHERE id = ?", created);
        createReminder(bob, conversationId, "Replacement",
                Instant.now().plus(3, ChronoUnit.HOURS), "NONE", null)
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void createReplaysWithTheSameIdempotencyKeyAndRejectsAChangedPayload() throws Exception {
        UUID conversationId = createGroup(alice, "Idempotent", bob);
        String key = UUID.randomUUID().toString();
        Instant remindAt = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        JsonNode first = createReminder(bob, conversationId, "Standup", remindAt, "DAILY", key)
                .andExpect(status().isCreated())
                .andReturnJson();
        JsonNode replay = createReminder(bob, conversationId, "Standup", remindAt, "DAILY", key)
                .andExpect(status().isOk())
                .andReturnJson();

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_reminders WHERE conversation_id = ?",
                Integer.class, conversationId)).isEqualTo(1);

        createReminder(bob, conversationId, "Different title", remindAt, "DAILY", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        createReminder(bob, conversationId, "Bad key", remindAt, "DAILY", "not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ----------------------------------------------------------------- helpers

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(ReminderFlowIT.class);
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

    private ReminderResult getJson(UUID conversationId, TestUser user) throws Exception {
        return new ReminderResult(mockMvc.perform(get(path(conversationId))
                .header("Authorization", "Bearer " + user.accessToken())));
    }

    private ReminderResult createReminder(TestUser actor, UUID conversationId, String title,
                                          Instant remindAt, String repeat, String key)
            throws Exception {
        var builder = post(path(conversationId))
                .header("Authorization", "Bearer " + actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "title", title,
                        "remindAt", remindAt.toString(),
                        "repeat", repeat)));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return new ReminderResult(mockMvc.perform(builder));
    }

    private ResultActions patchJson(UUID conversationId, UUID reminderId, TestUser actor,
                                    Object body) throws Exception {
        return mockMvc.perform(patch(path(conversationId) + "/" + reminderId)
                .header("Authorization", "Bearer " + actor.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteJson(UUID conversationId, UUID reminderId, TestUser actor)
            throws Exception {
        return mockMvc.perform(delete(path(conversationId) + "/" + reminderId)
                .header("Authorization", "Bearer " + actor.accessToken()));
    }

    private static String path(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/reminders";
    }

    private static List<String> titles(JsonNode list) {
        List<String> titles = new ArrayList<>();
        list.forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private void seedReminders(UUID conversationId, TestUser creator, int count, boolean fired) {
        Instant anchor = Instant.now().plus(2, ChronoUnit.HOURS);
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO conversation_reminders
                        (conversation_id, created_by, title, remind_at, anchor_at,
                         repeat_rule, fired_at)
                    VALUES (?, ?, ?, ?, ?, 'NONE', ?)
                    """,
                    conversationId,
                    creator.id(),
                    (fired ? "Fired " : "Seed ") + i,
                    OffsetDateTime.ofInstant(anchor.plusSeconds(i), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(anchor.plusSeconds(i), ZoneOffset.UTC),
                    fired ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        }
    }

    /** Small wrapper so a test can set expectations before reading the body. */
    private final class ReminderResult {
        private final ResultActions actions;

        private ReminderResult(ResultActions actions) {
            this.actions = actions;
        }

        private ReminderResult andExpect(
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
