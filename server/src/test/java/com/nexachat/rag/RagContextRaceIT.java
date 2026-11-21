package com.nexachat.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.testutil.FeatureItSupport;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real source transactions race a paused, synthetic provider response, including uncited context. */
class RagContextRaceIT extends FeatureItSupport {
    private static final FakeOpenAiServer LLM = startServer();
    private static final String QUESTION = "Please summarize the budget approval and the secret project value "
            + "from the team discussion, including the release plan, review status, rollout timing "
            + "and every other relevant milestone we discussed together.";

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.summary.llm.enabled", () -> true);
        registry.add("nexachat.summary.llm.api-key", () -> "test-only-key");
        registry.add("nexachat.summary.llm.base-url", LLM::baseUrl);
        registry.add("nexachat.summary.llm.model", () -> "test-model");
        registry.add("nexachat.summary.llm.timeout-ms", () -> 10_000);
    }

    @Autowired private ConversationRepository conversationRepository;
    @Autowired private RagQuestionCache cache;
    @Autowired private RagMessageIndex index;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger calls = new AtomicInteger();
    private CountDownLatch entered;
    private CountDownLatch release;
    private User caller;
    private User peer;
    private UUID conversationId;
    private UUID safeId;
    private UUID secretId;

    enum Mutation { EDIT, RECALL, OPT_OUT, EXPIRY }

    @BeforeEach
    void fixture() {
        calls.set(0);
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        caller = userRepository.save(User.create(ItPhoneRanges.nextPhone(getClass()),
                "x".repeat(60), "RAG race caller"));
        peer = userRepository.save(User.create(ItPhoneRanges.nextPhone(getClass()),
                "x".repeat(60), "RAG race peer"));
        jdbcTemplate.update("UPDATE users SET ai_summary_consent = TRUE WHERE id = ?", caller.getId());
        conversationId = conversationRepository.save(Conversation.group(caller, "RAG context race")).getId();
        for (User user : List.of(caller, peer)) {
            jdbcTemplate.update("""
                    INSERT INTO conversation_members (conversation_id, user_id, joined_at)
                    VALUES (?, ?, ?)
                    """, conversationId, user.getId(), OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));
        }
        safeId = message(caller, "The budget was approved for the release plan and project milestones.");
        secretId = message(peer, "The secret project value is alpha-bravo. The review and rollout are ready.");
        LLM.handle((exchange, body, authorization) -> {
            int call = calls.incrementAndGet();
            int citation = safeCitation(body);
            if (call == 1) {
                entered.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Paused provider was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(failure);
                }
            }
            String answer = call == 1 ? "The secret project value is alpha-bravo." : "The budget was approved.";
            FakeOpenAiServer.respond(exchange, 200, FakeOpenAiServer.jsonResponse(
                    "{\"answer\":" + FakeOpenAiServer.quote(answer) + ",\"citations\":[" + citation + "]}"));
        });
    }

    @AfterEach
    void cleanup() {
        release.countDown();
        executor.shutdownNow();
        if (conversationId != null) jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        if (caller != null) jdbcTemplate.update("DELETE FROM users WHERE id = ?", caller.getId());
        if (peer != null) jdbcTemplate.update("DELETE FROM users WHERE id = ?", peer.getId());
    }

    @AfterAll
    static void closeProvider() { LLM.close(); }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void anOldUncitedContextCannotRefillOrReplayAfterMutation(Mutation mutation) throws Exception {
        if (mutation == Mutation.EXPIRY) {
            jdbcTemplate.update("UPDATE messages SET expires_at = clock_timestamp() + interval '2 seconds' WHERE id = ?",
                    secretId);
        }
        RagQuestionCache.ContextSnapshot original = cache.contextSnapshot(conversationId);
        var oldRequest = executor.submit(this::ask);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("old generation reached the provider").isTrue();
            switch (mutation) {
                case EDIT -> jdbcTemplate.update("UPDATE messages SET content = 'The secret is now redacted.' WHERE id = ?", secretId);
                case RECALL -> jdbcTemplate.update("UPDATE messages SET deleted_at = now() WHERE id = ?", secretId);
                case OPT_OUT -> jdbcTemplate.update("INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                        conversationId, peer.getId());
                case EXPIRY -> jdbcTemplate.queryForObject("""
                        SELECT pg_sleep(GREATEST(0, EXTRACT(EPOCH FROM (expires_at - clock_timestamp()))) + 0.05)
                        FROM messages WHERE id = ?
                        """, Object.class, secretId);
            }
            assertThat(cache.contextUnchanged(conversationId, original)).isFalse();
        } finally {
            release.countDown();
        }
        JsonNode obsolete = oldRequest.get(8, TimeUnit.SECONDS);
        assertThat(obsolete.path("mode").asText()).isEqualTo("NOT_FOUND");
        assertThat(obsolete.toString()).doesNotContain("alpha-bravo");
        assertThat(cacheRows()).isZero();

        JsonNode current = ask();
        assertThat(current.path("mode").asText()).isEqualTo("LLM");
        assertThat(current.path("answer").asText()).isEqualTo("The budget was approved.");
        assertThat(current.path("citations").get(0).path("messageId").asText()).isEqualTo(safeId.toString());
        assertThat(ask().path("mode").asText()).isEqualTo("CACHE");
        assertThat(calls).hasValue(2);
    }

    @Test
    void sourceRevisionRollsBackAndNoOpUpdatesKeepValidCache() {
        RagQuestionCache.ContextSnapshot before = cache.contextSnapshot(conversationId);
        assertThat(save(before)).isTrue();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE messages SET content = 'A rolled-back edit' WHERE id = ?", secretId);
            assertThat(cache.contextSnapshot(conversationId).revision()).isGreaterThan(before.revision());
            status.setRollbackOnly();
        });
        assertThat(cache.contextSnapshot(conversationId)).isEqualTo(before);
        jdbcTemplate.update("UPDATE messages SET content = content WHERE id = ?", secretId);
        assertThat(cache.contextSnapshot(conversationId)).isEqualTo(before);
        assertThat(cache.findExact(conversationId, "lexical", "fixture")).isPresent();
        message(peer, "A new decision changes all derived answers.");
        assertThat(cache.contextSnapshot(conversationId).revision()).isGreaterThan(before.revision());
        assertThat(cache.findExact(conversationId, "lexical", "fixture")).isEmpty();
        assertThat(cache.findCandidates(conversationId, "lexical")).isEmpty();
        assertThat(save(before)).isFalse();
    }

    @Test
    void cachedAnswerExpiresNoLaterThanUncitedSource() {
        jdbcTemplate.update("UPDATE messages SET expires_at = clock_timestamp() + interval '5 minutes' WHERE id = ?",
                secretId);
        RagQuestionCache.ContextSnapshot snapshot = cache.contextSnapshot(conversationId);
        assertThat(save(snapshot)).isTrue();
        Instant expiry = jdbcTemplate.queryForObject("SELECT expires_at FROM rag_ask_cache WHERE conversation_id = ?",
                OffsetDateTime.class, conversationId).toInstant();
        assertThat(expiry).isEqualTo(snapshot.expiresAt());
    }

    @Test
    void cacheFillObservesAConcurrentSourceCommitBeforeItCanStore() throws Exception {
        RagQuestionCache.ContextSnapshot before = cache.contextSnapshot(conversationId);
        CountDownLatch sourceChanged = new CountDownLatch(1);
        CountDownLatch commitSource = new CountDownLatch(1);
        var sourceTransaction = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbcTemplate.update("UPDATE messages SET content = 'A committed edit' WHERE id = ?", secretId);
                    sourceChanged.countDown();
                    try {
                        if (!commitSource.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Source commit not released");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }));
        try {
            assertThat(sourceChanged.await(5, TimeUnit.SECONDS)).isTrue();
            var cacheFill = executor.submit(() -> save(before));
            commitSource.countDown();
            sourceTransaction.get(5, TimeUnit.SECONDS);
            assertThat(cacheFill.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(cacheRows()).isZero();
        } finally {
            commitSource.countDown();
        }
    }

    private boolean save(RagQuestionCache.ContextSnapshot snapshot) {
        RagMessage safe = index.findMessage(conversationId, safeId);
        return cache.saveIfConsentSnapshotMatches(caller.getId(), new SummaryConsentRepository.ConsentSnapshot(true, 0),
                conversationId, "lexical", "fixture", new float[]{1, 0},
                new RagQuestionCache.CachedAsk("The budget was approved.",
                        List.of(new RagCitation(safeId, safe.seq(), safe.content())), "LLM", List.of(safeId),
                        RagHashes.citationsHash(List.of(safe)), SummaryLanguage.EN, snapshot.revision(), snapshot.expiresAt()));
    }

    private JsonNode ask() throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/{id}/ask", conversationId)
                        .header("Authorization", "Bearer " + jwtService.generateAccessToken(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("question", QUESTION, "language", "EN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int cacheRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM rag_ask_cache WHERE conversation_id = ?",
                Integer.class, conversationId);
    }

    private UUID message(User sender, String content) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type, content)
                VALUES (?, ?, ?, ?, 'TEXT', ?)
                """, id, conversationId, sender.getId(), UUID.randomUUID(), content);
        return id;
    }

    private int safeCitation(String requestBody) throws java.io.IOException {
        String prompt = objectMapper.readTree(requestBody).path("messages").get(1).path("content").asText();
        // Context and priorTurns are nested in a JSON data block; select the budget item by its text.
        java.util.regex.Matcher item = java.util.regex.Pattern.compile(
                "\"i\":(\\d+),\"sender\":\"[^\"]+\",\"text\":\"[^\"]*budget[^\"]*\"").matcher(prompt);
        assertThat(item.find()).as("a citation for the safe budget source must be in the prompt").isTrue();
        return Integer.parseInt(item.group(1));
    }

    private static FakeOpenAiServer startServer() {
        try { return new FakeOpenAiServer(); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
