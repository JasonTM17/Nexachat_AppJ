package com.nexachat.rag;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryConsentRepository.ConsentSnapshot;
import com.nexachat.summary.SummaryLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

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
    private final AtomicLong consentEpoch = new AtomicLong();
    private final AtomicReference<RagQuestionCache.ContextSnapshot> context =
            new AtomicReference<>(new RagQuestionCache.ContextSnapshot(1, null));
    private final AtomicReference<Instant> admittedFrom = new AtomicReference<>(Instant.EPOCH);
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
        consentEpoch.set(1);
        nonConsentedRetrievalReached = new CountDownLatch(1);
        fact = RagTestSupport.message(conversationId, UUID.randomUUID(), 1, UUID.randomUUID(),
                "User", "The core migration is scheduled for March 14 at 02:00.");
        retrieval = new RetrievalResult(
                List.of(new ScoredMessage(fact, 0.9)), fact, List.of(), 0.9, 120,
                Map.of(fact.id(), fact));
        properties.getRetrieval().setExtractiveThreshold(1.01);

        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(memberRepository.findJoinedAt(eq(conversationId), any()))
                .thenAnswer(invocation -> Optional.of(admittedFrom.get()));
        when(consentRepository.isConsented(any())).thenAnswer(invocation -> consented.get());
        when(consentRepository.snapshot(any())).thenAnswer(invocation ->
                new ConsentSnapshot(consented.get(), consentEpoch.get()));
        when(embeddingService.embedQuestion(any(), anyBoolean())).thenAnswer(invocation -> {
            embeddingCalls.incrementAndGet();
            return new RagEmbeddingService.EmbeddingOutcome(new float[] {1f, 0f}, "test-embedding-model");
        });
        when(retrievalService.retrieve(any(), any(), any(), anyBoolean(), any())).thenAnswer(invocation -> {
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
        when(questionCache.contextSnapshot(any())).thenAnswer(invocation -> context.get());
        when(questionCache.contextUnchanged(any(), any())).thenAnswer(invocation ->
                context.get().equals(invocation.getArgument(1)));
        doAnswer(invocation -> {
            RagQuestionCache.CachedAsk answer = invocation.getArgument(6);
            boolean current = answer.expectedContentVersion() == context.get().revision()
                    && java.util.Objects.equals(answer.expectedContextExpiry(), context.get().expiresAt());
            if (current) {
                cachedByQuestionHash.put(invocation.getArgument(4), answer);
            }
            return current;
        }).when(questionCache).saveIfConsentSnapshotMatches(any(), any(), any(), any(), any(), any(), any());

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
        verify(retrievalService, times(1)).retrieve(eq(conversationId), any(), any(), anyBoolean(), any());
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
            consentEpoch.incrementAndGet();

            Future<AskResponse> nonConsentedAsk = executor.submit(
                    () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
            assertThat(nonConsentedRetrievalReached.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(nonConsentedAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode())
                    .isEqualTo(AskMode.NOT_FOUND);
        } finally {
            releaseProvider.countDown();
        }

        assertThat(consentedAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.NOT_FOUND);
        assertThat(answerProviderCalls.get()).isEqualTo(1);
        verify(retrievalService, times(2)).retrieve(eq(conversationId), any(), any(), anyBoolean(), any());
    }

    @Test
    void revokeAndReenableDoesNotJoinOrReturnAnOldConsentEpochAsk() throws Exception {
        CountDownLatch firstProviderEntered = new CountDownLatch(1);
        CountDownLatch secondProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int call = answerProviderCalls.incrementAndGet();
            (call == 1 ? firstProviderEntered : secondProviderEntered).countDown();
            if (!releaseProvider.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the provider was never released");
            }
            return new RagLlmAnswer(ANSWER, List.of(fact.id()));
        });

        Future<AskResponse> oldEpochAsk = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        assertThat(firstProviderEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        consented.set(false);
        consentEpoch.incrementAndGet();
        consented.set(true);
        consentEpoch.incrementAndGet();

        Future<AskResponse> newEpochAsk = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(secondProviderEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseProvider.countDown();
        }

        assertThat(oldEpochAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.NOT_FOUND);
        assertThat(newEpochAsk.get(WAIT_SECONDS, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.LLM);
        assertThat(answerProviderCalls.get()).isEqualTo(2);
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
        when(retrievalService.retrieve(any(), any(), any(), anyBoolean(), any()))
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

    @Test
    void distinctQuestionsAndFollowupHistoriesDoNotJoin() throws Exception {
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        answerWith(release, entered);
        List<Future<AskResponse>> asks = List.of(
                executor.submit(() -> service.ask(conversationId, userId, request(SummaryLanguage.EN))),
                executor.submit(() -> service.ask(conversationId, userId,
                        new AskRequest("Summarize the release decisions and review timing?", SummaryLanguage.EN))),
                executor.submit(() -> service.ask(conversationId, userId,
                        new AskRequest(QUESTION, SummaryLanguage.EN,
                                List.of(new AskPriorTurn("What was the plan?", "March migration."))))));
        try {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(collect(asks)).hasSize(3);
        assertThat(answerProviderCalls.get()).isEqualTo(3);
    }

    @Test
    void aNewAdmissionDoesNotJoinThePreviousMembershipFlight() throws Exception {
        assertNewVisibilityFlight(() -> admittedFrom.set(Instant.parse("2026-01-01T00:00:00Z")), false);
    }

    @Test
    void aNewContextRevisionDoesNotJoinOrReturnOldWork() throws Exception {
        assertNewVisibilityFlight(() -> context.set(new RagQuestionCache.ContextSnapshot(2, null)), true);
    }

    @Test
    void anExpiryBoundaryDoesNotJoinOrReturnOldWork() throws Exception {
        context.set(new RagQuestionCache.ContextSnapshot(1, Instant.now().plusSeconds(60)));
        assertNewVisibilityFlight(() -> context.set(new RagQuestionCache.ContextSnapshot(1, null)), true);
    }

    @Test
    void aStalledLeaderTimesOutWithoutExtraProviderWorkAndLaterRequestsRecover() throws Exception {
        properties.getAsk().setSingleFlightWaitMs(25);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        answerWith(releaseFirst, firstEntered);
        Future<AskResponse> first = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<AskResponse> follower = executor.submit(
                    () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
            assertThat(follower.get(1, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.NOT_FOUND);
            assertThat(answerProviderCalls.get()).isEqualTo(1);
            assertThat(embeddingCalls.get()).isEqualTo(1);

            // The timed-out follower detached only that stalled flight; a later caller can lead.
            doAnswer(invocation -> {
                        answerProviderCalls.incrementAndGet();
                        return new RagLlmAnswer("Recovered current answer.", List.of(fact.id()));
                    }).when(llmAnswerService).answer(any(), any(), any(), any(), any(), any(), any());
            AskResponse recovered = service.ask(conversationId, userId, request(SummaryLanguage.EN));
            assertThat(recovered.answer()).isEqualTo("Recovered current answer.");
            assertThat(answerProviderCalls.get()).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
        }
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void interruptedFollowerStopsPromptlyAndPreservesTheInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        answerWith(release, entered);
        Future<AskResponse> leader = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    service.ask(conversationId, userId, request(SummaryLanguage.EN));
                    return false;
                } catch (ApiException exception) {
                    return exception.getCode() == ApiErrorCode.INTERNAL_ERROR
                            && Thread.currentThread().isInterrupted();
                } finally {
                    Thread.interrupted();
                }
            });
            assertThat(interrupted.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(answerProviderCalls.get()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        leader.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void aLateOldLeaderCannotRemoveItsReplacementFlight() throws Exception {
        properties.getAsk().setSingleFlightWaitMs(25);
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch releaseNew = new CountDownLatch(1);
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    int call = answerProviderCalls.incrementAndGet();
                    (call == 1 ? oldEntered : newEntered).countDown();
                    (call == 1 ? releaseOld : releaseNew).await(WAIT_SECONDS, TimeUnit.SECONDS);
                    return new RagLlmAnswer(ANSWER, List.of(fact.id()));
                });
        Future<AskResponse> oldLeader = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(oldEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> service.ask(conversationId, userId, request(SummaryLanguage.EN)))
                    .get(1, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.NOT_FOUND);
            Future<AskResponse> replacement = executor.submit(
                    () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
            assertThat(newEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            releaseOld.countDown();
            oldLeader.get(WAIT_SECONDS, TimeUnit.SECONDS);
            cachedByQuestionHash.clear();
            assertThat(executor.submit(() -> service.ask(conversationId, userId, request(SummaryLanguage.EN)))
                    .get(1, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.NOT_FOUND);
            assertThat(answerProviderCalls.get()).isEqualTo(2);
            releaseNew.countDown();
            replacement.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseOld.countDown();
            releaseNew.countDown();
        }
    }

    private void assertNewVisibilityFlight(Runnable mutation, boolean suppressOld) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    int call = answerProviderCalls.incrementAndGet();
                    (call == 1 ? firstEntered : secondEntered).countDown();
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    return new RagLlmAnswer(ANSWER, List.of(fact.id()));
                });
        Future<AskResponse> first = executor.submit(
                () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
        try {
            assertThat(firstEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            mutation.run();
            Future<AskResponse> second = executor.submit(
                    () -> service.ask(conversationId, userId, request(SummaryLanguage.EN)));
            assertThat(secondEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(WAIT_SECONDS, TimeUnit.SECONDS).mode())
                    .isEqualTo(suppressOld ? AskMode.NOT_FOUND : AskMode.LLM);
            assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS).mode()).isEqualTo(AskMode.LLM);
        } finally {
            release.countDown();
        }
    }

    private void answerWith(CountDownLatch releaseProvider, CountDownLatch providerEntrances) {
        when(llmAnswerService.answer(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
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
