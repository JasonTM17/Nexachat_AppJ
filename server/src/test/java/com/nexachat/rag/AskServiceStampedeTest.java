package com.nexachat.rag;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A burst of identical concurrent ask requests is a cold-cache stampede: every
 * caller misses the exact and semantic caches and reaches the embedding and
 * answer providers. These tests count provider entries with latches and a fake
 * cache; they never sleep and never hit a real provider.
 */
class AskServiceStampedeTest {

    private static final int CALLERS = 8;
    private static final long WAIT_SECONDS = 10;
    private static final String QUESTION =
            "Summarize everything we decided about the migration plan?";
    private static final String ANSWER = "The core migration is on March 14.";

    private final RagProperties properties = new RagProperties();
    private final RagStats stats = new RagStats();
    private final RagEmbeddingService embeddingService = mock(RagEmbeddingService.class);
    private final RagRetrievalService retrievalService = mock(RagRetrievalService.class);
    private final RagQuestionCache questionCache = mock(RagQuestionCache.class);
    private final RagLlmAnswerService llmAnswerService = mock(RagLlmAnswerService.class);
    private final AskRateLimiter rateLimiter = mock(AskRateLimiter.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
    private final SummaryConsentRepository consentRepository = mock(SummaryConsentRepository.class);

    private final Map<String, RagQuestionCache.CachedAsk> cachedByQuestionHash = new ConcurrentHashMap<>();
    private final AtomicInteger embeddingCalls = new AtomicInteger();
    private final AtomicInteger answerProviderCalls = new AtomicInteger();
    private final AtomicBoolean consented = new AtomicBoolean();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private AskService service;
    private UUID conversationId;
    private UUID userId;
    private CountDownLatch nonConsentedRetrievalReached;
    private RagMessage fact;
    private RetrievalResult retrieval;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID();
        userId = UUID.randomUUID();
        consented.set(true);
        nonConsentedRetrievalReached = new CountDownLatch(1);
        fact = RagTestSupport.message(conversationId, UUID.randomUUID(), 1, UUID.randomUUID(),
                "User", "The core migration is scheduled for March 14 at 02:00.");
        retrieval = new RetrievalResult(
                List.of(new ScoredMessage(fact, 0.9)), fact, List.of(), 0.9, 120,
                Map.of(fact.id(), fact));
        properties.getRetrieval().setExtractiveThreshold(1.01);

        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.existsByConversation_IdAndUser_Id(eq(conversationId), any())).thenReturn(true);
        when(consentRepository.isConsented(any())).thenAnswer(invocation -> consented.get());
        when(embeddingService.embedQuestion(any(), anyBoolean())).thenAnswer(invocation -> {
            embeddingCalls.incrementAndGet();
            return new RagEmbeddingService.EmbeddingOutcome(new float[] {1f, 0f}, "test-embedding-model");
        });
        when(retrievalService.retrieve(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            Boolean allowExternal = invocation.getArgument(3);
            if (!allowExternal) {
                nonConsentedRetrievalReached.countDown();
            }
            return retrieval;
        });
        when(questionCache.findExact(any(), any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(cachedByQuestionHash.get(invocation.getArgument(2))));
        when(questionCache.findCandidates(any(), any())).thenReturn(List.of());
        when(questionCache.citationsValid(any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            cachedByQuestionHash.put(invocation.getArgument(2), invocation.getArgument(4));
            return null;
        }).when(questionCache).save(any(), any(), any(), any(), any());

        service = new AskService(rateLimiter, conversationRepository, memberRepository,
                consentRepository, embeddingService, retrievalService, questionCache,
                llmAnswerService, properties, stats, Clock.systemUTC());
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void concurrentIdenticalQuestionsMakeOneProviderCall() throws Exception {
        CountDownLatch callersAdmitted = new CountDownLatch(CALLERS);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            callersAdmitted.countDown();
            return null;
        }).when(rateLimiter).consume(userId);
        answerWith(releaseProvider, null);

        List<UUID> askers = new ArrayList<>();
        List<SummaryLanguage> languages = new ArrayList<>();
        for (int index = 0; index < CALLERS; index++) {
            askers.add(userId);
            languages.add(SummaryLanguage.EN);
        }
        List<Future<AskResponse>> futures = submit(askers, languages, QUESTION);
        try {
            assertThat(callersAdmitted.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProvider.countDown();
        }
        List<AskResponse> responses = collect(futures);

        // A request that was descheduled until after the leader finished pays
        // the embedding call needed for the exact-cache key, exactly like any
        // cache hit; the retrieval and answer-provider work is coalesced.
        assertThat(answerProviderCalls.get()).isEqualTo(1);
        assertThat(responses).hasSize(CALLERS).allSatisfy(response -> {
            assertThat(response.answer()).isEqualTo(ANSWER);
            assertThat(response.citations()).extracting(RagCitation::messageId).containsExactly(fact.id());
        });
        verify(rateLimiter, times(CALLERS)).consume(userId);
        verify(retrievalService, times(1)).retrieve(eq(conversationId), any(), any(), anyBoolean());
    }

    @Test
    void concurrentQuestionsFromDifferentUsersAreNotShared() throws Exception {
        UUID otherUserId = UUID.randomUUID();
        CountDownLatch providerEntrances = new CountDownLatch(2);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        answerWith(releaseProvider, providerEntrances);

        List<Future<AskResponse>> futures = submit(List.of(userId, otherUserId),
                List.of(SummaryLanguage.EN, SummaryLanguage.EN), QUESTION);
        try {
            assertThat(providerEntrances.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProvider.countDown();
        }
        collect(futures);

        assertThat(answerProviderCalls.get()).isEqualTo(2);
        verify(rateLimiter).consume(userId);
        verify(rateLimiter).consume(otherUserId);
    }

    @Test
    void requestWithoutConsentDoesNotJoinConsentedInFlightAsk() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        answerWith(releaseProvider, providerEntered);

        Future<AskResponse> consentedAsk = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            consented.set(false);

            Future<AskResponse> nonConsentedAsk = executor.submit(
                    () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
            assertThat(nonConsentedRetrievalReached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(nonConsentedAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode())
                    .isEqualTo(AskMode.NOT_FOUND);
        } finally {
            releaseProvider.countDown();
        }

        assertThat(consentedAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.LLM);
        assertThat(answerProviderCalls.get()).isEqualTo(1);
        verify(retrievalService, times(2)).retrieve(eq(conversationId), any(), any(), anyBoolean());
    }

    @Test
    void concurrentQuestionsInDifferentLanguagesAreNotShared() throws Exception {
        CountDownLatch providerEntrances = new CountDownLatch(2);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        answerWith(releaseProvider, providerEntrances);

        List<Future<AskResponse>> futures = submit(List.of(userId, userId),
                List.of(SummaryLanguage.EN, SummaryLanguage.VI), QUESTION);
        try {
            assertThat(providerEntrances.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProvider.countDown();
        }
        collect(futures);

        assertThat(answerProviderCalls.get()).isEqualTo(2);
    }

    @Test
    void aFailedAnswerIsNotReusedByTheNextRequest() {
        when(retrievalService.retrieve(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("retrieval is down"))
                .thenReturn(retrieval);
        answerWith(new CountDownLatch(0), null);

        assertThatThrownBy(() -> service.ask(conversationId, userId, request(SummaryLanguage.EN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retrieval is down");

        AskResponse response = service.ask(conversationId, userId, request(SummaryLanguage.EN));

        assertThat(response.mode()).isEqualTo(AskMode.LLM);
        assertThat(response.answer()).isEqualTo(ANSWER);
        assertThat(embeddingCalls.get()).isEqualTo(2);
        assertThat(answerProviderCalls.get()).isEqualTo(1);
    }

    private void answerWith(CountDownLatch releaseProvider, CountDownLatch providerEntrances) {
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            answerProviderCalls.incrementAndGet();
            if (providerEntrances != null) {
                providerEntrances.countDown();
            }
            if (!releaseProvider.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the provider was never released");
            }
            return new RagLlmAnswer(ANSWER, List.of(fact.id()));
        });
    }

    private List<Future<AskResponse>> submit(
            List<UUID> askers, List<SummaryLanguage> languages, String question) throws InterruptedException {
        List<Future<AskResponse>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int index = 0; index < askers.size(); index++) {
            UUID asker = askers.get(index);
            SummaryLanguage language = languages.get(index);
            futures.add(executor.submit(() -> {
                start.await();
                return service.ask(conversationId, asker, new AskRequest(question, language));
            }));
        }
        start.countDown();
        return futures;
    }

    private List<AskResponse> collect(List<Future<AskResponse>> futures) throws Exception {
        List<AskResponse> responses = new ArrayList<>();
        for (Future<AskResponse> future : futures) {
            responses.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        return responses;
    }

    private AskRequest request(SummaryLanguage language) {
        return new AskRequest(QUESTION, language);
    }
}
