package com.nexachat.reminder;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.notification.MockFcmGateway;
import com.nexachat.reminder.dto.CreateReminderRequest;
import com.nexachat.reminder.dto.UpdateReminderRequest;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Durable-scheduler coverage: fan-out, restart catch-up, lease recovery and
 * two schedulers racing over the same due rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReminderSchedulerIT {

    private static final String PHONE_PREFIX = ItPhoneRanges.prefixFor(ReminderSchedulerIT.class);

    @DynamicPropertySource
    static void reminderProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("nexachat.reminders.enabled", () -> "true");
        registry.add("nexachat.reminders.max-idle-ms", () -> "604800000");
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
    @Autowired
    private ReminderScheduler scheduler;
    @Autowired
    private ReminderService reminderService;
    @MockitoSpyBean
    private ConversationReminderRepository reminderRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockFcmGateway mockFcmGateway;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, ReminderSchedulerIT.class);
        String tag = "RMS" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.update(
                "DELETE FROM conversation_reminders WHERE created_by IN"
                        + " (SELECT id FROM users WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM user_device_tokens WHERE user_id IN"
                        + " (SELECT id FROM users WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
        mockFcmGateway.clear();
    }

    @Test
    void oneOffReminderPushesEveryMemberOnceThenStops() throws Exception {
        UUID conversationId = createGroup(alice, "Fan-out", bob, carol);
        registerDevice(alice, "token-alice");
        registerDevice(bob, "token-bob");
        registerDevice(carol, "token-carol");
        UUID reminderId = createReminder(bob, conversationId, "One off", "NONE");

        makeDue(reminderId, Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(1, ChronoUnit.MINUTES));
        mockFcmGateway.clear();

        assertThat(scheduler.runOnce()).isEqualTo(1);

        List<MockFcmGateway.SentPush> pushes = reminderPushes(reminderId);
        assertThat(pushes).hasSize(3);
        assertThat(pushes).allSatisfy(push -> {
            assertThat(push.data().get("type")).isEqualTo("REMINDER");
            assertThat(push.data().get("conversationId")).isEqualTo(conversationId.toString());
            assertThat(push.data().get("repeat")).isEqualTo("NONE");
            assertThat(push.data()).doesNotContainKey("title");
            assertThat(push.data()).doesNotContainKey("remindAt");
        });
        assertThat(pushes.stream().map(MockFcmGateway.SentPush::token).distinct().count())
                .isEqualTo(3);

        assertThat(firedAt(reminderId)).isNotNull();
        assertThat(upcoming(conversationId, bob)).isEmpty();

        // A second poll must not fire the completed one-off again.
        mockFcmGateway.clear();
        assertThat(scheduler.runOnce()).isZero();
        assertThat(mockFcmGateway.getSentPushes()).isEmpty();
    }

    @Test
    void dailyReminderAfterDowntimeSendsOneCatchUpAndAdvancesToTheFuture() throws Exception {
        UUID conversationId = createGroup(alice, "Catch-up", bob, carol);
        registerDevice(alice, "catchup-alice");
        registerDevice(bob, "catchup-bob");
        registerDevice(carol, "catchup-carol");
        UUID reminderId = createReminder(bob, conversationId, "Daily standup", "DAILY");

        Instant anchor = Instant.now().minus(10, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
        makeDue(reminderId, anchor, anchor);
        mockFcmGateway.clear();
        Instant beforeRun = Instant.now();

        assertThat(scheduler.runOnce()).isEqualTo(1);

        // One push per member, not one per missed day.
        assertThat(reminderPushes(reminderId)).hasSize(3);
        assertThat(firedAt(reminderId)).isNull();
        assertThat(lastFiredAt(reminderId)).isNotNull();

        Instant next = remindAt(reminderId);
        assertThat(next).isAfter(beforeRun);
        assertThat(next).isBefore(beforeRun.plus(2, ChronoUnit.HOURS));
        assertThat(upcoming(conversationId, bob)).hasSize(1);
        assertThat(upcoming(conversationId, bob).get(0).get("repeat").asText())
                .isEqualTo("DAILY");
    }

    @Test
    void twoSchedulersDoNotDoubleSend() throws Exception {
        UUID conversationId = createGroup(alice, "Race", bob);
        registerDevice(alice, "race-alice");
        registerDevice(bob, "race-bob");
        UUID first = createReminder(alice, conversationId, "Race one", "NONE");
        UUID second = createReminder(alice, conversationId, "Race two", "NONE");
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES);
        makeDue(first, due, due);
        makeDue(second, due, due);
        mockFcmGateway.clear();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> one = pool.submit(() -> {
                start.await();
                return scheduler.runOnce();
            });
            Future<Integer> two = pool.submit(() -> {
                start.await();
                return scheduler.runOnce();
            });
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Map<String, Long> perReminderAndToken = mockFcmGateway.getSentPushes().stream()
                .filter(push -> "REMINDER".equals(push.data().get("type")))
                .collect(Collectors.groupingBy(
                        push -> push.data().get("reminderId") + "|" + push.token(),
                        Collectors.counting()));
        assertThat(perReminderAndToken).hasSize(4);
        assertThat(perReminderAndToken.values()).allMatch(count -> count == 1L);
    }

    @Test
    void aLeasedReminderIsRecoveredOnlyAfterTheLeaseExpires() throws Exception {
        UUID conversationId = createGroup(alice, "Lease", bob);
        registerDevice(alice, "lease-alice");
        registerDevice(bob, "lease-bob");
        UUID reminderId = createReminder(alice, conversationId, "Lease test", "NONE");
        makeDue(reminderId, Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(1, ChronoUnit.MINUTES));
        jdbcTemplate.update("""
                UPDATE conversation_reminders
                SET claimed_at = now(), claim_token = ?
                WHERE id = ?
                """, UUID.randomUUID(), reminderId);
        mockFcmGateway.clear();

        // A fresh claim suppresses the crashed worker's row until the lease ends.
        assertThat(scheduler.runOnce()).isZero();
        assertThat(mockFcmGateway.getSentPushes()).isEmpty();

        jdbcTemplate.update("""
                UPDATE conversation_reminders
                SET claimed_at = now() - interval '20 minutes'
                WHERE id = ?
                """, reminderId);

        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(reminderPushes(reminderId)).hasSize(2);
        assertThat(firedAt(reminderId)).isNotNull();
    }

    @Test
    void aSharedDeviceTokenReceivesTheReminderOnce() throws Exception {
        UUID conversationId = createGroup(alice, "Shared device", bob);
        String sharedToken = "shared-device-token";
        registerDevice(alice, sharedToken);
        registerDevice(bob, sharedToken);
        UUID reminderId = createReminder(alice, conversationId, "Shared", "NONE");
        makeDue(reminderId, Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(1, ChronoUnit.MINUTES));
        mockFcmGateway.clear();

        assertThat(scheduler.runOnce()).isEqualTo(1);

        List<MockFcmGateway.SentPush> pushes = reminderPushes(reminderId);
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0).token()).isEqualTo(sharedToken);
    }

    @Test
    void aMemberWithoutADeviceTokenDoesNotBlockTheOthers() throws Exception {
        UUID conversationId = createGroup(alice, "Mixed devices", bob, carol);
        registerDevice(alice, "mixed-alice");
        registerDevice(carol, "mixed-carol");
        UUID reminderId = createReminder(alice, conversationId, "Mixed", "NONE");
        makeDue(reminderId, Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().minus(1, ChronoUnit.MINUTES));
        mockFcmGateway.clear();

        assertThat(scheduler.runOnce()).isEqualTo(1);

        List<MockFcmGateway.SentPush> pushes = reminderPushes(reminderId);
        assertThat(pushes).hasSize(2);
        assertThat(pushes.stream().map(MockFcmGateway.SentPush::token))
                .containsExactlyInAnyOrder("mixed-alice", "mixed-carol");
    }

    @Test
    void nextWakeTimeUsesUnclaimedDueAtOrClaimLeaseExpiry() throws Exception {
        UUID conversationId = createGroup(alice, "Wake times", bob);
        UUID reminderId = createReminder(alice, conversationId, "Wake time", "NONE");
        Instant dbNow = databaseNow();
        Instant dueAt = dbNow.plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        makeDue(reminderId, dueAt, dueAt);

        assertThat(reminderRepository.findNextWakeDelayMillis(600)).isBetween(598_000L, 600_000L);

        Instant claimedAt = databaseNow().truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("""
                UPDATE conversation_reminders
                SET remind_at = ?, claimed_at = ?, claim_token = ?
                WHERE id = ?
                """, timestamptz(Instant.now().minus(1, ChronoUnit.MINUTES)),
                timestamptz(claimedAt), UUID.randomUUID(), reminderId);

        assertThat(reminderRepository.findNextWakeDelayMillis(600)).isBetween(598_000L, 600_000L);
    }

    @Test
    void committedEarlierCreateRearmsTimerButRolledBackCreateDoesNot() throws Exception {
        UUID conversationId = createGroup(alice, "Wake event", bob);
        clearInvocations(reminderRepository);

        Instant laterAt = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        UUID laterId = new TransactionTemplate(transactionManager).execute(status ->
                reminderService.create(alice.id(), conversationId,
                        new CreateReminderRequest("Later", laterAt, "NONE"), null)
                        .reminder().id());
        verify(reminderRepository, times(1)).findNextWakeDelayMillis(600);

        clearInvocations(reminderRepository);
        Instant earlierAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reminderService.create(alice.id(), conversationId,
                        new CreateReminderRequest("Earlier", earlierAt, "NONE"), null));
        verify(reminderRepository, times(1)).findNextWakeDelayMillis(600);

        clearInvocations(reminderRepository);
        Instant updatedAt = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reminderService.update(alice.id(), conversationId, laterId,
                        new UpdateReminderRequest(null, updatedAt, null)));
        verify(reminderRepository, times(1)).findNextWakeDelayMillis(600);

        clearInvocations(reminderRepository);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                reminderService.delete(alice.id(), conversationId, laterId));
        verify(reminderRepository, times(1)).findNextWakeDelayMillis(600);

        clearInvocations(reminderRepository);
        Instant rolledBackAt = Instant.now().plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reminderService.create(alice.id(), conversationId,
                    new CreateReminderRequest("Rolled back", rolledBackAt, "NONE"), null);
            status.setRollbackOnly();
        });
        verify(reminderRepository, never()).findNextWakeDelayMillis(600);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_reminders WHERE conversation_id = ?",
                Integer.class, conversationId)).isEqualTo(1);
    }

    // ----------------------------------------------------------------- helpers

    private List<MockFcmGateway.SentPush> reminderPushes(UUID reminderId) {
        return mockFcmGateway.getSentPushes().stream()
                .filter(push -> reminderId.toString().equals(push.data().get("reminderId")))
                .toList();
    }

    private void makeDue(UUID reminderId, Instant remindAt, Instant anchorAt) {
        jdbcTemplate.update("""
                UPDATE conversation_reminders
                SET remind_at = ?, anchor_at = ?
                WHERE id = ?
                """,
                timestamptz(remindAt), timestamptz(anchorAt), reminderId);
    }

    private Instant remindAt(UUID reminderId) {
        return jdbcTemplate.queryForObject(
                "SELECT remind_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId).toInstant();
    }

    private Instant firedAt(UUID reminderId) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT fired_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId);
        return value == null ? null : value.toInstant();
    }

    private Instant lastFiredAt(UUID reminderId) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT last_fired_at FROM conversation_reminders WHERE id = ?",
                OffsetDateTime.class, reminderId);
        return value == null ? null : value.toInstant();
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamptz(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(ReminderSchedulerIT.class);
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

    private void registerDevice(TestUser user, String token) throws Exception {
        mockMvc.perform(post("/api/v1/users/device-token")
                        .header("Authorization", "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", token,
                                "platform", "ANDROID",
                                "deviceLabel", "Reminder IT"))))
                .andExpect(status().isOk());
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

    private UUID createReminder(TestUser actor, UUID conversationId, String title, String repeat)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/conversations/" + conversationId + "/reminders")
                                .header("Authorization", "Bearer " + actor.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "title", title,
                                        "remindAt", Instant.now().plus(30, ChronoUnit.DAYS)
                                                .truncatedTo(ChronoUnit.SECONDS).toString(),
                                        "repeat", repeat))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private JsonNode upcoming(UUID conversationId, TestUser reader) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/conversations/" + conversationId + "/reminders")
                                .header("Authorization", "Bearer " + reader.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
