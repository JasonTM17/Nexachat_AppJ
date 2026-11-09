package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import com.nexachat.summary.llm.LlmProperties;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagOptOutTest {

    private final InMemoryRagMessageIndex index = new InMemoryRagMessageIndex();
    private final RagProperties properties = new RagProperties();
    private final RagStats stats = new RagStats();
    private final RagEmbeddingService embeddings = new RagEmbeddingService(
            new RagEmbeddingProviders(properties), index, new VectorCache(1024), properties,
            stats, new Redactor());

    private FakeOpenAiServer server;
    private LlmClient client;
    private final UUID conversationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private UUID optedOutSender;

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void retrievalExcludesMessagesFromAnOptedOutAuthor() {
        UUID safeSender = UUID.randomUUID();
        optedOutSender = UUID.randomUUID();
        RagMessage safe = message(safeSender, 1, "We discussed the release notes.");
        RagMessage excluded = message(optedOutSender, 2, "The other secret is alpha-bravo.");
        index.add(safe);
        index.add(excluded);
        index.optOutMessage(excluded.id());

        RetrievalResult result = new RagRetrievalService(index, embeddings, properties)
                .retrieve(conversationId, "What is the other secret?", SummaryLanguage.EN, false);

        assertThat(result.messages().keySet()).doesNotContain(excluded.id());
        assertThat(result.messages().keySet()).contains(safe.id());
        assertThat(result.context()).allSatisfy(item ->
                assertThat(item.text()).doesNotContain("alpha-bravo"));
    }

    @Test
    void embeddingBatchDropsMessagesOptedOutAfterSelection() {
        optedOutSender = UUID.randomUUID();
        RagMessage message = message(optedOutSender, 1, "The other secret is alpha-bravo.");
        index.add(message);
        List<RagMessage> selectedBeforeOptOut = List.of(message);
        index.optOutMessage(message.id());

        RagEmbeddingService.VectorOutcome outcome = embeddings.ensureVectors(
                selectedBeforeOptOut, 10, false);

        assertThat(outcome.vectors()).isEmpty();
        assertThat(index.loadVectors(new RagEmbeddingProviders(properties).local().id(),
                List.of(message.id()))).isEmpty();
    }

    @Test
    void cacheCitationsFromAnOptedOutAuthorAreNotReplayable() {
        optedOutSender = UUID.randomUUID();
        RagMessage message = message(optedOutSender, 1, "The budget is 1,200 dollars.");
        index.add(message);
        RagQuestionCache cache = new RagQuestionCache(
                null, new ObjectMapper(), index, Clock.systemUTC(), new RagProperties());
        String hash = RagHashes.citationsHash(List.of(message));

        assertThat(cache.citationsValid(List.of(message.id()), hash)).isTrue();
        index.optOutMessage(message.id());
        assertThat(cache.citationsValid(List.of(message.id()), hash)).isFalse();
    }

    @Test
    void llmContextRechecksOptOutImmediatelyBeforeThePromptIsBuilt() throws Exception {
        startServer("{\"answer\":\"Safe answer.\",\"citations\":[2]}");
        UUID safeSender = UUID.randomUUID();
        optedOutSender = UUID.randomUUID();
        UUID safeId = UUID.randomUUID();
        UUID excludedId = UUID.randomUUID();
        RagContextItem excluded = new RagContextItem(1, List.of(excludedId), 1, "U1",
                "The other secret is alpha-bravo.");
        RagContextItem safe = new RagContextItem(2, List.of(safeId), 2, "U2",
                "We agreed to ship the release on Friday.");
        Map<UUID, RagMessage> messages = Map.of(
                excludedId, new RagMessage(excludedId, conversationId, 1, optedOutSender, "U1",
                        excluded.text(), Instant.EPOCH, null, null),
                safeId, new RagMessage(safeId, conversationId, 2, safeSender, "U2",
                        safe.text(), Instant.EPOCH, null, null));

        // The context was retrieved before the opt-out committed.
        ConversationAiOptOuts optOuts = optOuts(Set.of(optedOutSender));
        RagLlmAnswerService service = llmService(optOuts, consent(true));
        RagLlmAnswer answer = service.answer(userId, conversationId, "What did we agree to ship?",
                SummaryLanguage.EN, List.of(excluded, safe), messages);

        assertThat(answer).isNotNull();
        assertThat(answer.citationIds()).containsExactly(safeId);
        String body = server.requests().get(0).body();
        assertThat(body).doesNotContain("alpha-bravo");
        assertThat(body).contains("ship the release");
    }

    @Test
    void noExternalCallIsMadeWhenEveryContextMessageIsOptedOut() throws Exception {
        startServer("{\"answer\":\"Unused.\",\"citations\":[1]}");
        optedOutSender = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RagContextItem item = new RagContextItem(1, List.of(messageId), 1, "U1",
                "The other secret is alpha-bravo.");
        Map<UUID, RagMessage> messages = Map.of(messageId, new RagMessage(messageId, conversationId,
                1, optedOutSender, "U1", item.text(), Instant.EPOCH, null, null));

        RagLlmAnswer answer = llmService(optOuts(Set.of(optedOutSender)), consent(true))
                .answer(userId, conversationId, "What is the secret?", SummaryLanguage.EN,
                        List.of(item), messages);

        assertThat(answer).isNull();
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void revokedConsentAtPromptBuildSkipsTheExternalCall() throws Exception {
        startServer("{\"answer\":\"Unused.\",\"citations\":[1]}");
        UUID messageId = UUID.randomUUID();
        RagContextItem item = new RagContextItem(1, List.of(messageId), 1, "U1",
                "The server migration is scheduled for March 14.");
        Map<UUID, RagMessage> messages = Map.of(messageId, new RagMessage(messageId, conversationId,
                1, UUID.randomUUID(), "U1", item.text(), Instant.EPOCH, null, null));
        SummaryConsentRepository consent = mock(SummaryConsentRepository.class);
        when(consent.isConsented(userId)).thenReturn(true, false);

        RagLlmAnswer answer = llmService(ConversationAiOptOuts.none(), consent)
                .answer(userId, conversationId, "When is the migration?", SummaryLanguage.EN,
                        List.of(item), messages);

        assertThat(answer).isNull();
        assertThat(server.requestCount()).isZero();
    }

    private RagLlmAnswerService llmService(ConversationAiOptOuts optOuts, SummaryConsentRepository consent) {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setEnabled(true);
        llmProperties.setApiKey("test-key");
        llmProperties.setModel("test-model");
        llmProperties.setBaseUrl(server.baseUrl());
        llmProperties.setTimeoutMs(5_000);
        client = new LlmClient(llmProperties, new ObjectMapper());
        return new RagLlmAnswerService(client, new RagPromptBuilder(new ObjectMapper(), new Redactor()),
                new RagOutputValidator(new ObjectMapper()), new CircuitBreaker(Clock.systemUTC()),
                consent, stats, optOuts);
    }

    private SummaryConsentRepository consent(boolean value) {
        SummaryConsentRepository consent = mock(SummaryConsentRepository.class);
        when(consent.isConsented(userId)).thenReturn(value);
        return consent;
    }

    private ConversationAiOptOuts optOuts(Set<UUID> userIds) {
        AtomicReference<Set<UUID>> optedOut = new AtomicReference<>(Set.copyOf(userIds));
        return new ConversationAiOptOuts() {
            @Override
            public Set<UUID> optedOutUserIds(UUID targetConversationId) {
                return optedOut.get();
            }

            @Override
            public Set<UUID> optedOutConversationIds(UUID targetUserId) {
                return Set.of();
            }

            @Override
            public boolean hasOptedOutMessages(UUID targetConversationId) {
                return !optedOut.get().isEmpty();
            }

            @Override
            public boolean isOptedOut(UUID targetConversationId, UUID targetUserId) {
                return optedOut.get().contains(targetUserId);
            }
        };
    }

    private void startServer(String content) throws Exception {
        server = new FakeOpenAiServer();
        server.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(
                exchange, 200, FakeOpenAiServer.jsonResponse(content)));
    }

    private RagMessage message(UUID senderId, long seq, String content) {
        return new RagMessage(UUID.randomUUID(), conversationId, seq, senderId, "User", content,
                Instant.EPOCH, null, null);
    }
}
