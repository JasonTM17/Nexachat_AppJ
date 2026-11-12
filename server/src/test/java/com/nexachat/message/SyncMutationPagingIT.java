package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B1 convergence oracle for the delta-sync mutation feed.
 *
 * <p>The client synced all messages (its {@code lastSeq} is above every
 * message), disconnects, and a burst of edits or recalls lands on rows with
 * {@code seq <= lastSeq}. On reconnect the client follows the public sync API
 * exactly as the Android {@code SyncEngine} does: it keeps its saved
 * {@code serverTime} as {@code since} for every page and only persists the
 * final response time after the drain. The assertion is convergence, not
 * event-count equality: every message must reach its final state.
 *
 * <p>Today {@link MessageService#syncDelta} truncates the edited/deleted feeds
 * to {@code limitPerConv} without raising {@code hasMore} or returning a
 * continuation cursor, so the client stops after the first page and the tail
 * of the burst is lost. These cases are the red contract for the change-cursor
 * repair and are intentionally disabled until that repair lands.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncMutationPagingIT {

    private static final int PAGE_LIMIT = 50;
    private static final int MAX_DRAIN_PAGES = 8;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
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

    private record TestUser(UUID id, String accessToken) {
    }

    private record ClientState(String content, boolean deleted) {
    }

    private record DrainResult(int pages, boolean lastHasMore) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "SYNCMUT" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
    }

    @Test
    void zeroMutationsReachEveryFinalState() throws Exception {
        assertMutationsConverge(0, MutationKind.EDIT, false);
        assertMutationsConverge(0, MutationKind.RECALL, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 49, 50, 51, 60, 101})
    @Disabled("red: sync mutation overflow, fixed by change cursors")
    void editsBehindTheClientCursorReachEveryFinalState(int editCount) throws Exception {
        assertMutationsConverge(editCount, MutationKind.EDIT, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 49, 50, 51, 60, 101})
    @Disabled("red: sync mutation overflow, fixed by change cursors")
    void recallsBehindTheClientCursorReachEveryFinalState(int recallCount) throws Exception {
        assertMutationsConverge(recallCount, MutationKind.RECALL, false);
    }

    @Test
    @Disabled("red: sync mutation overflow, fixed by change cursors")
    void equalTimestampEditsReachEveryFinalState() throws Exception {
        assertMutationsConverge(60, MutationKind.EDIT, true);
    }

    @Test
    @Disabled("red: sync mutation overflow, fixed by change cursors")
    void equalTimestampRecallsReachEveryFinalState() throws Exception {
        assertMutationsConverge(60, MutationKind.RECALL, true);
    }

    private enum MutationKind {
        EDIT,
        RECALL
    }

    /**
     * One full client cycle: seed {@code mutationCount} messages, sync them,
     * mutate every one behind the cursor, then drain the public sync API and
     * check final-state equality.
     */
    private void assertMutationsConverge(int mutationCount, MutationKind kind, boolean equalTimestamps)
            throws Exception {
        UUID conversationId = createGroup("sync-mutation-" + kind + "-" + mutationCount + "-" + System.nanoTime());
        int messageCount = Math.max(1, mutationCount);

        Map<UUID, ClientState> cached = new LinkedHashMap<>();
        List<UUID> messageIds = new ArrayList<>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            String content = "original-" + i;
            UUID messageId = messageService.send(alice.id(), conversationId,
                            new SendMessageRequest(UUID.randomUUID(), "TEXT", content, null, List.of()))
                    .message().id();
            messageIds.add(messageId);
            cached.put(messageId, new ClientState(content, false));
        }
        Map<UUID, ClientState> expected = new LinkedHashMap<>(cached);

        JsonNode baseline = sync(bob, conversationId, 0L, null, PAGE_LIMIT);
        JsonNode baselineDelta = singleDelta(baseline);
        long clientCursor = baselineDelta.get("maxSeq").asLong();
        Instant clientSince = Instant.parse(baseline.get("serverTime").asText());

        for (int i = 0; i < mutationCount; i++) {
            UUID messageId = messageIds.get(i);
            if (kind == MutationKind.EDIT) {
                String edited = "edited-" + i;
                messageService.edit(alice.id(), conversationId, messageId, edited);
                expected.put(messageId, new ClientState(edited, false));
            } else {
                messageService.delete(alice.id(), conversationId, messageId);
                expected.put(messageId, new ClientState(null, true));
            }
        }

        if (equalTimestamps && mutationCount > 0) {
            Instant shared = clientSince.plusMillis(1).truncatedTo(ChronoUnit.MICROS);
            if (kind == MutationKind.EDIT) {
                jdbcTemplate.update("""
                        UPDATE messages SET edited_at = ?
                        WHERE conversation_id = ? AND deleted_at IS NULL AND content LIKE 'edited-%'
                        """, Timestamp.from(shared), conversationId);
            } else {
                jdbcTemplate.update("UPDATE messages SET deleted_at = ? WHERE conversation_id = ? AND deleted_at IS NOT NULL",
                        Timestamp.from(shared), conversationId);
            }
        }

        Map<UUID, ClientState> applied = new LinkedHashMap<>(cached);
        DrainResult drain = drain(bob, conversationId, clientCursor, clientSince, applied);

        List<UUID> unreached = expected.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(applied.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .toList();
        String detail = unreached.stream()
                .limit(5)
                .map(id -> id + " expected=" + expected.get(id) + " actual=" + applied.get(id))
                .collect(Collectors.joining("; "));

        assertThat(unreached)
                .as("%s mutations behind cursor=%d must all be reachable; pages=%d lastHasMore=%s; first mismatches: %s",
                        mutationCount, clientCursor, drain.pages(), drain.lastHasMore(), detail)
                .isEmpty();
    }

    /**
     * Follows the documented continuation rules: same {@code since} on every
     * page, advance {@code lastSeq} only from {@code newMessages}, and (once
     * additive change cursors exist) echo the response cursor. A page that
     * claims more work without a usable continuation is where the drain stops.
     */
    private DrainResult drain(TestUser reader, UUID conversationId, long lastSeq, Instant since,
                              Map<UUID, ClientState> applied) throws Exception {
        long cursor = lastSeq;
        Long changeCursor = null;
        int pages = 0;
        boolean hasMore = false;
        while (pages < MAX_DRAIN_PAGES) {
            JsonNode response = sync(reader, conversationId, cursor, since, PAGE_LIMIT, changeCursor);
            JsonNode delta = singleDelta(response);
            apply(delta, applied);
            pages++;
            hasMore = delta.path("hasMore").asBoolean(false);
            if (!hasMore) {
                break;
            }

            Long nextChangeCursor = delta.hasNonNull("changeCursor") ? delta.get("changeCursor").asLong() : null;
            if (nextChangeCursor != null && !nextChangeCursor.equals(changeCursor)) {
                changeCursor = nextChangeCursor;
                continue;
            }

            long nextSeq = cursor;
            for (JsonNode message : delta.withArray("newMessages")) {
                nextSeq = Math.max(nextSeq, message.get("seq").asLong());
            }
            if (nextSeq > cursor) {
                cursor = nextSeq;
                continue;
            }
            break;
        }
        return new DrainResult(pages, hasMore);
    }

    private static void apply(JsonNode delta, Map<UUID, ClientState> applied) {
        for (JsonNode message : delta.withArray("editedMessages")) {
            applied.put(UUID.fromString(message.get("id").asText()),
                    new ClientState(message.get("content").asText(null), false));
        }
        for (JsonNode deletedId : delta.withArray("deletedMessageIds")) {
            applied.put(UUID.fromString(deletedId.asText()), new ClientState(null, true));
        }
        // Additive v2 shape: current rows including tombstones, when present.
        for (JsonNode message : delta.withArray("changes")) {
            boolean deleted = !message.path("deletedAt").isNull()
                    && !message.path("deletedAt").isMissingNode();
            applied.put(UUID.fromString(message.get("id").asText()),
                    new ClientState(deleted ? null : message.get("content").asText(null), deleted));
        }
    }

    private JsonNode sync(TestUser user, UUID conversationId, long lastSeq, Instant since, int limit)
            throws Exception {
        return sync(user, conversationId, lastSeq, since, limit, null);
    }

    private JsonNode sync(TestUser user, UUID conversationId, long lastSeq, Instant since, int limit,
                          Long changeCursor) throws Exception {
        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", lastSeq);
        if (changeCursor != null) {
            watermark.put("changeCursor", changeCursor);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watermarks", List.of(watermark));
        body.put("limitPerConv", limit);
        if (since != null) {
            body.put("since", since.toString());
        }
        MvcResult result = mockMvc.perform(post("/api/v1/sync")
                        .header("Authorization", bearer(user))
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
        String phone = "+8493" + (1 + ThreadLocalRandom.current().nextInt(9))
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
