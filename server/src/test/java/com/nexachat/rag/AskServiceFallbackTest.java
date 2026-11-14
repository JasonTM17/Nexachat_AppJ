package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.FakeOpenAiServer;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryConsentRepository.ConsentSnapshot;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import com.nexachat.summary.llm.LlmFallbackProperties;
import com.nexachat.summary.llm.LlmProperties;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskServiceFallbackTest {

    private final InMemoryRagMessageIndex index = new InMemoryRagMessageIndex();
    private final RagProperties properties = new RagProperties();
    private final RagStats stats = new RagStats();
    private final RagEmbeddingService embeddings = new RagEmbeddingService(
            new RagEmbeddingProviders(properties), index, new VectorCache(1024), properties,
            stats, new Redactor());
    private final RagRetrievalService retrieval = new RagRetrievalService(index, embeddings, properties);

    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
    private final SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);
    private final RagQuestionCache questionCache = mock(RagQuestionCache.class);
    private final AskRateLimiter rateLimiter = mock(AskRateLimiter.class);

    private FakeOpenAiServer primary;
    private FakeOpenAiServer fallback;
    private LlmClient client;
    private AskService service;
    private UUID conversationId;
    private UUID userId;
    private UUID senderId;

    @AfterEach
    void closeResources() {
        if (client != null) {
            client.close();
        }
        if (primary != null) {
            primary.close();
        }
        if (fallback != null) {
            fallback.close();
        }
    }

    @Test
    void fallbackAnswerIsRecordedInUsageAndStatsAndConsumesOneRateLimitToken() throws Exception {
        startServers();
        properties.getRetrieval().setExtractiveThreshold(1.01);
        addMessage(conversationId, senderId, 1, "The server migration is scheduled for March 14 at 02:00.");
        setConsent(true);
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        AskResponse response = service.ask(conversationId, userId,
                new AskRequest("Summarize everything we decided about the migration plan?", SummaryLanguage.EN));

        assertThat(response.mode()).isEqualTo(AskMode.LLM);
        assertThat(response.usage().provider()).isEqualTo("fallback");
        assertThat(response.usage().llmCalls()).isEqualTo(1);
        verify(rateLimiter, times(1)).consume(userId);
        assertThat(stats.snapshot().llmCalls()).isEqualTo(1);
        assertThat(stats.snapshot().llmFallbackCalls()).isEqualTo(1);
    }

    @Test
    void primaryAnswerIsRecordedAsPrimary() throws Exception {
        startServers();
        properties.getRetrieval().setExtractiveThreshold(1.01);
        addMessage(conversationId, senderId, 1, "The server migration is scheduled for March 14 at 02:00.");
        setConsent(true);
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 200,
                FakeOpenAiServer.jsonResponse("{\"answer\":\"The migration is scheduled for March 14.\",\"citations\":[1]}")));

        AskResponse response = service.ask(conversationId, userId,
                new AskRequest("Summarize everything we decided about the migration plan?", SummaryLanguage.EN));

        assertThat(response.mode()).isEqualTo(AskMode.LLM);
        assertThat(response.usage().provider()).isEqualTo("primary");
        assertThat(stats.snapshot().llmCalls()).isEqualTo(1);
        assertThat(stats.snapshot().llmFallbackCalls()).isZero();
    }

    @Test
    void bothProvidersFailingDegradesWithoutAnLlmCall() throws Exception {
        startServers();
        properties.getRetrieval().setExtractiveThreshold(1.01);
        addMessage(conversationId, senderId, 1, "The server migration is scheduled for March 14 at 02:00.");
        setConsent(true);
        primary.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 500, "primary down"));
        fallback.handle((exchange, body, authorization) -> FakeOpenAiServer.respond(exchange, 503, "fallback down"));

        AskResponse response = service.ask(conversationId, userId,
                new AskRequest("Summarize everything we decided about the migration plan?", SummaryLanguage.EN));

        assertThat(response.mode()).isEqualTo(AskMode.NOT_FOUND);
        assertThat(response.usage().provider()).isNull();
        assertThat(response.usage().llmCalls()).isZero();
        assertThat(stats.snapshot().llmCalls()).isZero();
        assertThat(stats.snapshot().llmFailures()).isEqualTo(1);
    }

    @Test
    void cacheHitNeverCallsEitherProvider() throws Exception {
        startServers();
        RagMessage fact = addMessage(conversationId, senderId, 1, "The budget is 1,200 dollars.");
        RagQuestionCache.CachedAsk cached = new RagQuestionCache.CachedAsk(
                "The budget is 1,200 dollars.",
                List.of(new RagCitation(fact.id(), 1, "The budget is 1,200 dollars.")),
                "EXTRACTIVE", List.of(fact.id()), "hash", SummaryLanguage.EN);
        when(questionCache.findExact(any(), any(), any())).thenReturn(Optional.of(cached));
        setConsent(true);

        AskResponse response = service.ask(conversationId, userId,
                new AskRequest("How much budget was approved?", null));

        assertThat(response.mode()).isEqualTo(AskMode.CACHE);
        assertThat(response.usage().cached()).isTrue();
        assertThat(response.usage().provider()).isNull();
        assertThat(primary.requestCount()).isZero();
        assertThat(fallback.requestCount()).isZero();
    }

    private void startServers() throws Exception {
        primary = new FakeOpenAiServer();
        fallback = new FakeOpenAiServer();
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setEnabled(true);
        llmProperties.setApiKey("primary-key");
        llmProperties.setModel("primary-model");
        llmProperties.setBaseUrl(primary.baseUrl());
        llmProperties.setTimeoutMs(5_000);
        LlmFallbackProperties fallbackProperties = new LlmFallbackProperties();
        fallbackProperties.setEnabled(true);
        fallbackProperties.setApiKey("fallback-key");
        fallbackProperties.setModel("fallback-model");
        fallbackProperties.setBaseUrl(fallback.baseUrl());
        fallbackProperties.setTimeoutMs(5_000);
        client = new LlmClient(llmProperties, fallbackProperties, new ObjectMapper());
        RagLlmAnswerService llmAnswerService = new RagLlmAnswerService(
                client, new RagPromptBuilder(new ObjectMapper(), new Redactor()),
                new RagOutputValidator(new ObjectMapper()), new CircuitBreaker(Clock.systemUTC()),
                consentRepository, stats);
        service = new AskService(
                rateLimiter, conversationRepository, memberRepository, consentRepository,
                embeddings, retrieval, questionCache, llmAnswerService, properties, stats,
                Clock.systemUTC());

        conversationId = UUID.randomUUID();
        userId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.findJoinedAt(conversationId, userId)).thenReturn(java.util.Optional.of(java.time.Instant.EPOCH));
        when(questionCache.citationsValid(any(), any())).thenReturn(true);
    }

    private RagMessage addMessage(UUID conversationId, UUID senderId, long seq, String content) {
        RagMessage message = RagTestSupport.message(conversationId, UUID.randomUUID(), seq,
                senderId, "User", content);
        index.add(message);
        return message;
    }

    private void setConsent(boolean consented) {
        when(consentRepository.isConsented(userId)).thenReturn(consented);
        when(consentRepository.snapshot(userId)).thenReturn(new ConsentSnapshot(consented, consented ? 1 : 0));
    }
}
