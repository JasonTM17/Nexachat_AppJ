package com.nexachat.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class SummaryApiIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL",
                "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SummaryWindowService windowService;
    @Autowired
    private SummaryConsentRepository consentRepository;
    @Autowired
    private SummaryCacheRepository cacheRepository;
    @Autowired
    private SummaryRequestLimitRepository requestLimitRepository;

    private User caller;
    private User peer;
    private User outsider;
    private UUID conversationId;

    @BeforeEach
    void createFixture() {
        caller = createUser("Summary caller");
        peer = createUser("Summary peer");
        outsider = createUser("Summary outsider");
        Conversation conversation = conversationRepository.save(Conversation.group(caller, "Summary fixture"));
        conversationId = conversation.getId();
        addMember(caller.getId(), 0);
        addMember(peer.getId(), 0);
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        List<UUID> userIds = new ArrayList<>();
        if (caller != null) {
            userIds.add(caller.getId());
        }
        if (peer != null) {
            userIds.add(peer.getId());
        }
        if (outsider != null) {
            userIds.add(outsider.getId());
        }
        for (UUID userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void endpointReturnsStructuredCachedSummaryAndDeniesNonMembers() throws Exception {
        List<TestMessage> messages = addUsableMessages(5);
        String requestBody = requestBody("UNREAD", null, false);

        MvcResult first = postSummary(caller, requestBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.messageCount").value(5))
                .andExpect(jsonPath("$.generatedBy").value("LOCAL"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.keyPoints").isArray())
                .andExpect(jsonPath("$.decisions").isArray())
                .andExpect(jsonPath("$.actionItems").isArray())
                .andExpect(jsonPath("$.questions").isArray())
                .andExpect(jsonPath("$.topics").isArray())
                .andExpect(jsonPath("$.participants").isArray())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        String summaryId = firstBody.path("summaryId").asText();
        Set<String> allowedIds = messages.stream().map(message -> message.id().toString())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        assertCitationsAreInWindow(firstBody, allowedIds);

        MvcResult cached = postSummary(caller, requestBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andReturn();
        assertThat(objectMapper.readTree(cached.getResponse().getContentAsString())
                .path("summaryId").asText()).isEqualTo(summaryId);

        postSummary(outsider, requestBody)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void windowUsesUnreadWatermarkAndExcludesDeletedSystemBlankAndAttachedMessages() {
        TestMessage readMessage = addMessage(caller.getId(), "Already read", "TEXT", Instant.now(), null);
        jdbcTemplate.update("UPDATE conversation_members SET last_read_seq = ? "
                        + "WHERE conversation_id = ? AND user_id = ?",
                readMessage.seq(), conversationId, caller.getId());

        TestMessage includedOne = addMessage(peer.getId(), "Unread one", "TEXT", Instant.now(), null);
        addMessage(peer.getId(), "Tombstone", "TEXT", Instant.now(), Instant.now());
        TestMessage attachment = addMessage(peer.getId(), "Caption for file", "TEXT", Instant.now(), null);
        jdbcTemplate.update("INSERT INTO message_attachments "
                        + "(id, message_id, owner_id, storage_key, original_name, mime_type, size_bytes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), attachment.id(), peer.getId(), UUID.randomUUID().toString(),
                "note.txt", "text/plain", 1L);
        addMessage(peer.getId(), null, "SYSTEM", Instant.now(), null);
        addMessage(peer.getId(), "   ", "TEXT", Instant.now(), null);
        TestMessage includedTwo = addMessage(caller.getId(), "Unread two", "TEXT", Instant.now(), null);

        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, false)).window();

        assertThat(window.messages()).extracting(SummaryMessage::id)
                .containsExactly(includedOne.id(), includedTwo.id());
        assertThat(window.fromSeq()).isEqualTo(includedOne.seq());
        assertThat(window.toSeq()).isEqualTo(includedTwo.seq());
    }

    @Test
    void scopesAreCappedAtFiveHundredLatestMessagesInSequenceOrder() {
        List<Long> sequences = new ArrayList<>();
        for (int index = 0; index < 505; index++) {
            sequences.add(addMessage(caller.getId(), "Summary cap message " + index,
                    "TEXT", Instant.now(), null).seq());
        }

        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, false)).window();

        assertThat(window.messageCount()).isEqualTo(500);
        assertThat(window.messages()).extracting(SummaryMessage::seq)
                .containsExactlyElementsOf(sequences.subList(5, sequences.size()));
        assertThat(window.fromSeq()).isEqualTo(sequences.get(5));
        assertThat(window.toSeq()).isEqualTo(sequences.get(504));
    }

    @Test
    void lastNScopeReturnsTheLatestRequestedMessages() {
        List<TestMessage> messages = addUsableMessages(8);

        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, false)).window();

        assertThat(window.messages()).extracting(SummaryMessage::id)
                .containsExactlyElementsOf(messages.subList(3, messages.size()).stream()
                        .map(TestMessage::id).toList());
    }

    @Test
    void todayScopeUsesUtcMidnightAsItsInclusiveBoundary() {
        Instant start = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        TestMessage before = addMessage(caller.getId(), "Before UTC day", "TEXT",
                start.minus(1, ChronoUnit.MICROS), null);
        TestMessage atStart = addMessage(caller.getId(), "At UTC midnight", "TEXT", start, null);
        TestMessage beforeEnd = addMessage(peer.getId(), "Before next UTC day", "TEXT",
                start.plus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.MICROS), null);
        addMessage(peer.getId(), "At next UTC midnight", "TEXT", start.plus(1, ChronoUnit.DAYS), null);

        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.EN, false)).window();

        assertThat(window.messages()).extracting(SummaryMessage::id)
                .containsExactly(atStart.id(), beforeEnd.id());
        assertThat(window.messages()).extracting(SummaryMessage::id)
                .doesNotContain(before.id());
    }

    @Test
    void editDeleteAndRefreshProduceNewCacheEntries() throws Exception {
        List<TestMessage> messages = addUsableMessages(6);
        String ordinaryRequest = requestBody("LAST_N", 50, false);
        JsonNode first = response(postSummary(caller, ordinaryRequest).andExpect(status().isOk()).andReturn());

        jdbcTemplate.update("UPDATE messages SET content = ?, edited_at = now() WHERE id = ?",
                "Edited summary text", messages.get(0).id());
        JsonNode afterEdit = response(postSummary(caller, ordinaryRequest)
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false)).andReturn());
        assertThat(afterEdit.path("summaryId").asText()).isNotEqualTo(first.path("summaryId").asText());

        jdbcTemplate.update("UPDATE messages SET content = NULL, deleted_at = now() WHERE id = ?",
                messages.get(0).id());
        JsonNode afterDelete = response(postSummary(caller, ordinaryRequest)
                .andExpect(status().isOk()).andExpect(jsonPath("$.messageCount").value(5))
                .andExpect(jsonPath("$.cached").value(false)).andReturn());
        assertThat(afterDelete.path("summaryId").asText()).isNotEqualTo(afterEdit.path("summaryId").asText());

        JsonNode refreshed = response(postSummary(caller, requestBody("LAST_N", 50, true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false)).andReturn());
        assertThat(refreshed.path("summaryId").asText()).isNotEqualTo(afterDelete.path("summaryId").asText());
    }

    @Test
    void recallPurgesOnlySummaryWindowsContainingRecalledMessage() throws Exception {
        List<TestMessage> recalledWindow = addUsableMessages(5);
        postSummary(caller, requestBody("LAST_N", 5, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        List<TestMessage> retainedWindow = addUsableMessages(5);
        postSummary(caller, requestBody("LAST_N", 5, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        assertThat(cachedWindows()).containsExactly(
                new SummaryWindowRange(recalledWindow.get(0).seq(), recalledWindow.get(4).seq()),
                new SummaryWindowRange(retainedWindow.get(0).seq(), retainedWindow.get(4).seq()));

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}/messages/{messageId}",
                        conversationId, recalledWindow.get(0).id())
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(caller)))
                .andExpect(status().isNoContent());

        assertThat(cachedWindows()).containsExactly(
                new SummaryWindowRange(retainedWindow.get(0).seq(), retainedWindow.get(4).seq()));
    }

    @Test
    void textEditPurgesOnlySummaryWindowsContainingEditedMessage() throws Exception {
        List<TestMessage> editedWindow = addUsableMessages(5);
        postSummary(caller, requestBody("LAST_N", 5, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        List<TestMessage> retainedWindow = addUsableMessages(5);
        postSummary(caller, requestBody("LAST_N", 5, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        assertThat(cachedWindows()).containsExactly(
                new SummaryWindowRange(editedWindow.get(0).seq(), editedWindow.get(4).seq()),
                new SummaryWindowRange(retainedWindow.get(0).seq(), retainedWindow.get(4).seq()));

        mockMvc.perform(patch("/api/v1/conversations/{conversationId}/messages/{messageId}",
                        conversationId, editedWindow.get(0).id())
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "Edited summary source text"))))
                .andExpect(status().isOk());

        assertThat(cachedWindows()).containsExactly(
                new SummaryWindowRange(retainedWindow.get(0).seq(), retainedWindow.get(4).seq()));
    }

    @Test
    void invalidRangeAndTooFewMessagesUseTypedErrors() throws Exception {
        postSummary(caller, requestBody("LAST_N", 4, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        addUsableMessages(4);
        postSummary(caller, requestBody("UNREAD", null, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOO_FEW_MESSAGES"));
    }

    @Test
    void consentDefaultsToFalseAndCanBeReadFromItsPersistedColumn() {
        assertThat(consentRepository.isConsented(caller.getId())).isFalse();
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
        assertThat(consentRepository.isConsented(caller.getId())).isTrue();
    }

    @Test
    void cacheInsertRaceReturnsOneWinner() throws Exception {
        addUsableMessages(5);
        SummaryWindow window = windowService.load(conversationId, caller.getId(),
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, false)).window();
        String hash = SummaryService.contentHash(window);
        SummaryGeneration generation = new SummaryGeneration(
                new SummaryContent("Concurrent result", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SummaryCacheRepository.SavedSummary> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return cacheRepository.saveIfAbsent(conversationId, window, hash, "LOCAL",
                        SummaryLanguage.EN, generation);
            });
            Future<SummaryCacheRepository.SavedSummary> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return cacheRepository.saveIfAbsent(conversationId, window, hash, "LOCAL",
                        SummaryLanguage.EN, generation);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            SummaryCacheRepository.SavedSummary firstResult = first.get(10, TimeUnit.SECONDS);
            SummaryCacheRepository.SavedSummary secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.entry().id()).isEqualTo(secondResult.entry().id());
            assertThat(List.of(firstResult.inserted(), secondResult.inserted()).stream()
                    .filter(Boolean::booleanValue).count()).isEqualTo(1);
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM message_summaries WHERE conversation_id = ?", Integer.class,
                    conversationId);
            assertThat(count).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void minuteAndDailyCountersEnforceTheirIndependentCaps() {
        Instant now = Instant.now();
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();

        for (int index = 0; index < 10; index++) {
            assertThat(requestLimitRepository.tryConsume(caller.getId(), "MINUTE", minuteStart, 10)).isTrue();
        }
        assertThat(requestLimitRepository.tryConsume(caller.getId(), "MINUTE", minuteStart, 10)).isFalse();

        for (int index = 0; index < 60; index++) {
            assertThat(requestLimitRepository.tryConsume(caller.getId(), "DAY", dayStart, 60)).isTrue();
        }
        assertThat(requestLimitRepository.tryConsume(caller.getId(), "DAY", dayStart, 60)).isFalse();
    }

    private User createUser(String displayName) {
        String phone = "+849" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L));
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }

    private void addMember(UUID userId, long lastReadSeq) {
        jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id, last_read_seq) "
                        + "VALUES (?, ?, ?)",
                conversationId, userId, lastReadSeq);
    }

    private List<TestMessage> addUsableMessages(int count) {
        List<TestMessage> messages = new ArrayList<>();
        String[] samples = {
                "The team decided to use the local summary engine.",
                "Please finish the API schema by Friday.",
                "I will write the regression tests today.",
                "Can we merge after the tests pass?",
                "The next action is to prepare the demo.",
                "The backend review is scheduled for tomorrow."
        };
        for (int index = 0; index < count; index++) {
            messages.add(addMessage(index % 2 == 0 ? caller.getId() : peer.getId(),
                    samples[index % samples.length], "TEXT", Instant.now(), null));
        }
        return messages;
    }

    private TestMessage addMessage(UUID senderId, String content, String type, Instant createdAt, Instant deletedAt) {
        UUID messageId = UUID.randomUUID();
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO messages
                    (id, conversation_id, sender_id, client_message_id, type, content, created_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS text), ?, CAST(? AS timestamptz))
                RETURNING seq
                """, Long.class, messageId, conversationId, senderId, UUID.randomUUID(), type,
                content, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                deletedAt == null ? null : OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC));
        return new TestMessage(messageId, sequence);
    }

    private org.springframework.test.web.servlet.ResultActions postSummary(User user, String body) throws Exception {
        String token = jwtService.generateAccessToken(user);
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/summary")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String requestBody(String scope, Integer limit, boolean refresh) throws Exception {
        Map<String, Object> body = limit == null
                ? Map.of("scope", scope, "language", "EN", "refresh", refresh)
                : Map.of("scope", scope, "limit", limit, "language", "EN", "refresh", refresh);
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<SummaryWindowRange> cachedWindows() {
        return jdbcTemplate.query("""
                        SELECT from_seq, to_seq
                        FROM message_summaries
                        WHERE conversation_id = ?
                        ORDER BY from_seq, to_seq
                        """,
                (rs, rowNum) -> new SummaryWindowRange(rs.getLong("from_seq"), rs.getLong("to_seq")),
                conversationId);
    }

    private static void assertCitationsAreInWindow(JsonNode body, Set<String> allowedIds) {
        for (String section : List.of("keyPoints", "decisions", "actionItems", "questions")) {
            for (JsonNode item : body.path(section)) {
                assertThat(item.path("messageIds").isArray()).isTrue();
                assertThat(item.path("messageIds").isEmpty()).isFalse();
                for (JsonNode messageId : item.path("messageIds")) {
                    assertThat(allowedIds).contains(messageId.asText());
                }
            }
        }
    }

    private record TestMessage(UUID id, long seq) {
    }

    private record SummaryWindowRange(long fromSeq, long toSeq) {
    }
}
