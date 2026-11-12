package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.SendMessageRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H1 latch schedule: can a row that allocated {@code seq} first commit after a
 * later row, and be skipped by a delta sync that runs between the two commits?
 *
 * <p>The schedule is deterministic and uses only test-owned DB objects:
 * <ol>
 *   <li>a transaction (A) inserts {@code h1-first} and its {@code AFTER INSERT}
 *       trigger blocks on a transaction-scoped advisory lock, so A holds an
 *       allocated {@code seq} but cannot reach its conversation-row update;</li>
 *   <li>transaction B inserts {@code h1-second} with a larger {@code seq} and
 *       commits;</li>
 *   <li>the client syncs: it sees B, advances {@code maxSeq} to B's seq, and
 *       cannot see uncommitted A;</li>
 *   <li>the lock is released, A commits with a smaller {@code seq}.</li>
 * </ol>
 *
 * <p>If the next sync from the returned {@code maxSeq} never reaches A, the
 * delta feed skipped a committed message: allocation order is not commit order
 * and {@code maxSeq} is not a completion proof. The fix is to allocate the
 * conversation change version (and, if chosen, serialize sends) under the
 * conversation row lock <em>before</em> the message insert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageCommitOrderIT {

    private static final long GATE_LOCK_KEY = 918_273_645L;
    private static final int PAGE_LIMIT = 50;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
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
    private MessageService messageService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Connection gate;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "COMMITORDER" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
    }

    @AfterEach
    void cleanup() {
        releaseGate();
        executor.shutdownNow();
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS h1_message_insert_gate ON messages");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS h1_message_insert_gate()");
        } catch (RuntimeException cleanupFailure) {
            // The disposable test database is dropped by the gate command; a
            // blocked DDL must not mask the test's own assertion.
            System.err.println("H1 trigger cleanup deferred: " + cleanupFailure.getMessage());
        }
    }

    @Test
    @Disabled("red: sync commit-order skip, fixed by change cursors allocated under the conversation lock")
    void smallerSequenceCommittedAfterALaterOneIsStillReachable() throws Exception {
        UUID conversationId = createGroup("commit-order-" + System.nanoTime());
        JsonNode baseline = sync(conversationId, 0L, null);
        JsonNode baselineDelta = singleDelta(baseline);
        long baselineMax = baselineDelta.get("maxSeq").asLong();
        Instant baselineSince = Instant.parse(baseline.get("serverTime").asText());

        installGateTrigger();
        gate = openGate();

        Future<MessageResponse> first = executor.submit(() -> messageService.send(
                alice.id(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "h1-first", null, List.of())).message());

        awaitBlockedOnGate();

        Future<MessageResponse> second = executor.submit(() -> messageService.send(
                bob.id(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "h1-second", null, List.of())).message());
        MessageResponse secondMessage = second.get(15, TimeUnit.SECONDS);
        assertThat(secondMessage.seq()).isGreaterThan(baselineMax);

        JsonNode betweenCommits = sync(conversationId, baselineMax, baselineSince);
        JsonNode betweenDelta = singleDelta(betweenCommits);
        assertThat(contents(betweenDelta.get("newMessages"))).containsExactly("h1-second");
        assertThat(betweenDelta.get("maxSeq").asLong()).isEqualTo(secondMessage.seq());

        releaseGate();
        MessageResponse firstMessage = first.get(20, TimeUnit.SECONDS);
        assertThat(firstMessage.seq()).isLessThan(secondMessage.seq());

        Map<String, Long> applied = new LinkedHashMap<>();
        applyNewMessages(betweenDelta, applied);

        JsonNode afterCommit = sync(conversationId,
                betweenDelta.get("maxSeq").asLong(),
                Instant.parse(betweenCommits.get("serverTime").asText()));
        applyNewMessages(singleDelta(afterCommit), applied);

        assertThat(applied)
                .as("a client that advanced maxSeq to %d must still reach the earlier-allocated message",
                        secondMessage.seq())
                .containsKeys("h1-first", "h1-second");
    }

    private void installGateTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION h1_message_insert_gate() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.content = 'h1-first' THEN
                        PERFORM pg_advisory_xact_lock(%d);
                    END IF;
                    RETURN NEW;
                END;
                $$;
                """.formatted(GATE_LOCK_KEY));
        jdbcTemplate.execute("""
                CREATE TRIGGER h1_message_insert_gate
                AFTER INSERT ON messages
                FOR EACH ROW EXECUTE FUNCTION h1_message_insert_gate()
                """);
    }

    private Connection openGate() throws SQLException {
        Connection connection = DriverManager.getConnection(
                env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"),
                env("IT_DB_USER", "nexachat"),
                env("IT_DB_PASSWORD", "nexachat"));
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, GATE_LOCK_KEY);
            statement.execute();
        }
        return connection;
    }

    private void releaseGate() {
        if (gate == null) {
            return;
        }
        try {
            if (!gate.isClosed()) {
                gate.rollback();
                gate.close();
            }
        } catch (SQLException ignored) {
            // The connection is disposable; the advisory lock dies with it.
        } finally {
            gate = null;
        }
    }

    private void awaitBlockedOnGate() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_locks l
                    JOIN pg_stat_activity a ON a.pid = l.pid
                    WHERE l.locktype = 'advisory'
                      AND NOT l.granted
                      AND a.datname = current_database()
                    """, Integer.class);
            assertThat(waiting).isGreaterThanOrEqualTo(1);
        });
    }

    private static List<String> contents(JsonNode messages) {
        List<String> values = new ArrayList<>();
        for (JsonNode message : messages) {
            values.add(message.get("content").asText());
        }
        return values;
    }

    private static void applyNewMessages(JsonNode delta, Map<String, Long> applied) {
        for (JsonNode message : delta.get("newMessages")) {
            applied.put(message.get("content").asText(), message.get("seq").asLong());
        }
    }

    private JsonNode sync(UUID conversationId, long lastSeq, Instant since) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watermarks", List.of(Map.of(
                "conversationId", conversationId.toString(),
                "lastSeq", lastSeq)));
        body.put("limitPerConv", PAGE_LIMIT);
        if (since != null) {
            body.put("since", since.toString());
        }
        MvcResult result = mockMvc.perform(post("/api/v1/sync")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode singleDelta(JsonNode response) {
        JsonNode deltas = response.get("deltas");
        assertThat(deltas).as("sync response must contain the requested conversation").hasSize(1);
        return deltas.get(0);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = "+8494" + (1 + ThreadLocalRandom.current().nextInt(9))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        var result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID createGroup(String title) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title, "memberIds", List.of(bob.id().toString())))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
