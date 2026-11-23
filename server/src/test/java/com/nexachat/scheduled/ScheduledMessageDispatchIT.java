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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Durable-dispatcher coverage: send+mark, replay sends, membership/suspension
 * outcomes at dispatch time, the retry backoff, the 24h dead-letter, lease
 * recovery and two schedulers racing over the same due rows.
 *
 * <p>The background timer is disabled ({@code nexachat.scheduled.enabled=false}
 * in the it profile); every run is driven explicitly through
 * {@link ScheduledMessageScheduler#runOnce()} for determinism.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledMessageDispatchIT {

    private static final String PHONE_PREFIX =
            ItPhoneRanges.prefixFor(ScheduledMessageDispatchIT.class);

    @DynamicPropertySource
    static void scheduledProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("nexachat.scheduled.enabled", () -> "false");
        registry.add("nexachat.scheduled.claim-lease-seconds", () -> "60");
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
    private ScheduledMessageScheduler scheduler;
    @Autowired
    private ScheduledMessageRepository scheduledMessageRepository;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, ScheduledMessageDispatchIT.class);
        String tag = "SMD" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    @BeforeEach
    void clearScheduled() {
        jdbcTemplate.update(
                "DELETE FROM scheduled_messages WHERE sender_id IN"
                        + " (SELECT id FROM users WHERE phone LIKE ?)",
                PHONE_PREFIX + "%");
        jdbcTemplate.update(
                "UPDATE users SET suspended_at = NULL, suspended_reason = NULL,"
                        + " suspended_by = NULL, deleted_at = NULL WHERE phone LIKE ?",
                PHONE_PREFIX + "%");
    }

    // --------------------------------------------------------------- success

    @Test
    void aDueRowIsSentThroughTheNormalPathAndMarkedSent() throws Exception {
        UUID conversationId = createGroup(alice, "Dispatch", bob);
        UUID clientMessageId = UUID.randomUUID();
        UUID scheduledId = createScheduled(bob, conversationId, "hop 9h",
                Instant.now().plus(2, ChronoUnit.HOURS), clientMessageId);
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_code, body, sent_message_id, claimed_at"
                        + " FROM scheduled_messages WHERE id = ?", scheduledId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("failure_code")).isNull();
        assertThat(row.get("body")).isEqualTo("");
        assertThat(row.get("claimed_at")).isNull();

        UUID sentMessageId = (UUID) row.get("sent_message_id");
        assertThat(sentMessageId).isNotNull();
        Map<String, Object> message = jdbcTemplate.queryForMap(
                "SELECT sender_id, content, type, client_message_id"
                        + " FROM messages WHERE id = ?", sentMessageId);
        assertThat(message.get("sender_id")).isEqualTo(bob.id());
        assertThat(message.get("content")).isEqualTo("hop 9h");
        assertThat(message.get("type")).isEqualTo("TEXT");
        assertThat(message.get("client_message_id")).isEqualTo(clientMessageId);

        // The row is terminal — a second run must not re-send it.
        assertThat(scheduler.runOnce()).isZero();
    }

    @Test
    void aReplayedSendStillMarksTheRowSent() throws Exception {
        UUID conversationId = createGroup(alice, "Replay send", bob);
        UUID clientMessageId = UUID.randomUUID();

        // A message with the key already exists (a previous dispatch crashed
        // between send and mark). The next dispatch must link to it, not
        // duplicate it.
        UUID existingMessageId = sendMessage(bob, conversationId, clientMessageId,
                "already sent");
        UUID scheduledId = createScheduled(bob, conversationId, "already sent",
                Instant.now().plus(2, ChronoUnit.HOURS), clientMessageId);
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));
        int before = countMessages(conversationId);

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, sent_message_id FROM scheduled_messages WHERE id = ?",
                scheduledId);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_message_id")).isEqualTo(existingMessageId);
        assertThat(countMessages(conversationId)).isEqualTo(before);
    }

    @Test
    void aRowNotYetDueIsNotClaimedEarly() throws Exception {
        UUID conversationId = createGroup(alice, "Future", bob);
        createScheduled(bob, conversationId, "later",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());

        assertThat(scheduler.runOnce()).isZero();
        assertThat(countMessages(conversationId)).isZero();
    }

    // ------------------------------------------------------- business failures

    @Test
    void aSenderWhoLeftTheConversationFailsWithNotMember() throws Exception {
        UUID conversationId = createGroup(alice, "Left", bob);
        UUID scheduledId = createScheduled(bob, conversationId, "left early",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));

        // The membership disappears between schedule and dispatch.
        jdbcTemplate.update(
                "DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                conversationId, bob.id());

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_code, body, sent_message_id"
                        + " FROM scheduled_messages WHERE id = ?", scheduledId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("NOT_MEMBER");
        assertThat(row.get("body")).isEqualTo("");
        assertThat(row.get("sent_message_id")).isNull();
        assertThat(countMessages(conversationId)).isZero();

        // Terminal: never retried.
        assertThat(scheduler.runOnce()).isZero();
    }

    @Test
    void aDeletedSenderFailsWithAccountDeleted() throws Exception {
        UUID conversationId = createGroup(alice, "Tombstone", bob);
        UUID scheduledId = createScheduled(bob, conversationId, "ghost",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));

        // Tombstone the sender directly; the account-deletion path also
        // cancels PENDING rows, but a row claimed before the deletion lands
        // here (amendment 1 covers the dispatch-time re-check).
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", bob.id());

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_code FROM scheduled_messages WHERE id = ?",
                scheduledId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("ACCOUNT_DELETED");

        jdbcTemplate.update("UPDATE users SET deleted_at = NULL WHERE id = ?", bob.id());
    }

    @Test
    void aSuspendedSenderReleasesTheClaimAndRetriesAfterUnsuspend() throws Exception {
        UUID conversationId = createGroup(alice, "Suspended", bob);
        UUID scheduledId = createScheduled(bob, conversationId, "after unsuspend",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));
        jdbcTemplate.update(
                "UPDATE users SET suspended_at = now() WHERE id = ?", bob.id());

        // The send is refused with ACCOUNT_SUSPENDED, which is retryable:
        // the claim is released and the row parked for the 15s base backoff.
        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, claimed_at, claim_token, attempt_count, next_attempt_at, body"
                        + " FROM scheduled_messages WHERE id = ?", scheduledId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("claimed_at")).isNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("body")).isEqualTo("after unsuspend");
        Object nextAttempt = row.get("next_attempt_at");
        assertThat(nextAttempt).isNotNull();
        Instant wake = toInstant(nextAttempt);
        assertThat(wake).isAfter(Instant.now().plus(5, ChronoUnit.SECONDS));
        assertThat(wake).isBefore(Instant.now().plus(25, ChronoUnit.SECONDS));

        // The parked row is not retried before its backoff fires.
        assertThat(scheduler.runOnce()).isZero();

        // Once the backoff passed and the account is healthy, the row sends.
        jdbcTemplate.update(
                "UPDATE users SET suspended_at = NULL WHERE id = ?", bob.id());
        jdbcTemplate.update(
                "UPDATE scheduled_messages SET next_attempt_at = NULL WHERE id = ?", scheduledId);
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM scheduled_messages WHERE id = ?",
                String.class, scheduledId)).isEqualTo("SENT");
    }

    @Test
    void aRowPastTheDeadLetterHorizonIsFailedNotSent() throws Exception {
        UUID conversationId = createGroup(alice, "Dead letter", bob);
        UUID scheduledId = createScheduled(bob, conversationId, "a day late",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        makeDue(scheduledId, Instant.now().minus(25, ChronoUnit.HOURS));

        assertThat(scheduler.runOnce()).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_code, body"
                        + " FROM scheduled_messages WHERE id = ?", scheduledId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_code")).isEqualTo("FAILED_RETRY_EXHAUSTED");
        assertThat(row.get("body")).isEqualTo("");
        assertThat(countMessages(conversationId)).isZero();
    }

    // ----------------------------------------------------------- claim protocol

    @Test
    void aLeasedRowIsRecoveredOnlyAfterTheLeaseExpires() throws Exception {
        UUID conversationId = createGroup(alice, "Lease", bob);
        UUID scheduledId = createScheduled(bob, conversationId, "lease test",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        makeDue(scheduledId, Instant.now().minus(1, ChronoUnit.MINUTES));
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now(), claim_token = ?
                WHERE id = ?
                """, UUID.randomUUID(), scheduledId);

        // A fresh claim suppresses the crashed worker's row until the lease ends.
        assertThat(scheduler.runOnce()).isZero();
        assertThat(countMessages(conversationId)).isZero();

        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = now() - interval '5 minutes'
                WHERE id = ?
                """, scheduledId);

        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM scheduled_messages WHERE id = ?",
                String.class, scheduledId)).isEqualTo("SENT");
        assertThat(countMessages(conversationId)).isEqualTo(1);
    }

    @Test
    void twoSchedulersDoNotDoubleSend() throws Exception {
        UUID conversationId = createGroup(alice, "Race", bob);
        UUID first = createScheduled(bob, conversationId, "race one",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        UUID second = createScheduled(bob, conversationId, "race two",
                Instant.now().plus(2, ChronoUnit.HOURS), UUID.randomUUID());
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES);
        makeDue(first, due);
        makeDue(second, due);

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
            int resolved = one.get(30, TimeUnit.SECONDS) + two.get(30, TimeUnit.SECONDS);
            assertThat(resolved).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        // Exactly one message per scheduled row, sent exactly once.
        assertThat(countMessages(conversationId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_messages"
                        + " WHERE status <> 'SENT' AND sender_id = ?",
                Integer.class, bob.id())).isZero();
    }

    @Test
    void wakeTimeCoversDueRetryAndLeaseExpiry() {
        UUID conversationId = randomConversationSeed();
        Instant dbNow = databaseNow();
        UUID id = seedRow(conversationId, bob.id(), dbNow.plus(10, ChronoUnit.MINUTES));

        assertThat(scheduledMessageRepository.findNextWakeDelayMillis(60))
                .isBetween(598_000L, 600_000L);

        Instant claimedAt = databaseNow().truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET send_at = ?, claimed_at = ?, claim_token = ?
                WHERE id = ?
                """, timestamptz(dbNow.minus(1, ChronoUnit.MINUTES)),
                timestamptz(claimedAt), UUID.randomUUID(), id);
        assertThat(scheduledMessageRepository.findNextWakeDelayMillis(60))
                .isBetween(58_000L, 60_000L);

        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET claimed_at = NULL, claim_token = NULL,
                    next_attempt_at = ?
                WHERE id = ?
                """, timestamptz(dbNow.plus(5, ChronoUnit.MINUTES)), id);
        assertThat(scheduledMessageRepository.findNextWakeDelayMillis(60))
                .isBetween(298_000L, 300_000L);
    }

    // ---------------------------------------------------------------- helpers

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(ScheduledMessageDispatchIT.class);
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

    private UUID createScheduled(TestUser actor, UUID conversationId, String body,
                                 Instant sendAt, UUID clientMessageId) throws Exception {
        MvcResult result = mockMvc.perform(post(path(conversationId))
                        .header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", clientMessageId.toString(),
                                "body", body,
                                "sendAt", sendAt.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID sendMessage(TestUser actor, UUID conversationId, UUID clientMessageId,
                             String content) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", clientMessageId.toString(),
                                "type", "TEXT",
                                "content", content))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private void makeDue(UUID scheduledId, Instant sendAt) {
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET send_at = ?
                WHERE id = ?
                """, timestamptz(sendAt), scheduledId);
    }

    private int countMessages(UUID conversationId) {
        // Group creation already writes one SYSTEM line; only TEXT rows are
        // client-visible sends, which is what dispatch produces.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND type = 'TEXT'",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class).toInstant();
    }

    /** {@code queryForMap} hands back the driver's raw type — Timestamp for
     * timestamptz under pgjdbc's default mapping. */
    private static Instant toInstant(Object column) {
        if (column instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return ((java.sql.Timestamp) column).toInstant();
    }

    private UUID randomConversationSeed() {
        // A group without due rows; the seeded row drives the wake query.
        try {
            return createGroup(alice, "Wake " + UUID.randomUUID(), bob);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private UUID seedRow(UUID conversationId, UUID senderId, Instant sendAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO scheduled_messages
                    (id, conversation_id, sender_id, client_message_id, body, send_at)
                VALUES (?, ?, ?, ?, 'wake', ?)
                """, id, conversationId, senderId, UUID.randomUUID(), timestamptz(sendAt));
        return id;
    }

    private static String path(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/scheduled-messages";
    }

    private static OffsetDateTime timestamptz(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
