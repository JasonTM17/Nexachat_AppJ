package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.summary.llm.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryServiceTest {

    @Mock
    private SummaryRateLimiter rateLimiter;
    @Mock
    private SummaryWindowService windowService;
    @Mock
    private SummaryProviderSelector providerSelector;
    @Mock
    private SummaryCacheRepository cacheRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final SummaryWindow window = window(conversationId, 5);
    private SummaryService service;

    @BeforeEach
    void setUp() {
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository);
    }

    @Test
    void contentHashChangesWhenMessageIsEdited() {
        SummaryWindow original = window(conversationId, 5);
        SummaryMessage first = original.messages().get(0);
        SummaryMessage edited = new SummaryMessage(first.id(), first.seq(), first.senderId(), first.senderName(),
                first.text(), first.createdAt(), Instant.parse("2026-11-05T12:00:00Z"));
        SummaryWindow changed = new SummaryWindow(conversationId, 0, 0,
                List.of(edited, original.messages().get(1), original.messages().get(2),
                        original.messages().get(3), original.messages().get(4)));

        assertThat(SummaryService.contentHash(original)).isNotEqualTo(SummaryService.contentHash(changed));
    }

    @Test
    void contentHashChangesWhenTextChangesWithoutTimestampChange() {
        SummaryWindow original = window(conversationId, 5);
        SummaryMessage first = original.messages().get(0);
        SummaryMessage edited = new SummaryMessage(first.id(), first.seq(), first.senderId(), first.senderName(),
                "Edited message content", first.createdAt(), first.editedAt());
        SummaryWindow changed = new SummaryWindow(conversationId, 0, 0,
                List.of(edited, original.messages().get(1), original.messages().get(2),
                        original.messages().get(3), original.messages().get(4)));

        assertThat(SummaryService.contentHash(original)).isNotEqualTo(SummaryService.contentHash(changed));
    }

    @Test
    void tooFewUsableMessagesReturnsTypedDomainErrorBeforeProviderSelection() {
        SummaryWindow shortWindow = window(conversationId, 4);
        SummaryRequest request = request(false);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(shortWindow, false, 0L, null));

        assertThatThrownBy(() -> service.summarize(conversationId, userId, request))
                .isInstanceOf(SummaryTooFewMessagesException.class);
        verify(providerSelector, never()).prepare(org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void refreshSkipsLookupAndReplacesExistingCacheEntry() {
        SummaryRequest request = request(true);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration generated = generation("LLM", "Fresh output", null);
        SummaryCacheEntry entry = cacheEntry(generated, "LLM");
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        when(cacheRepository.replace(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(generated))).thenReturn(entry);

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.overview()).isEqualTo("Fresh output");
        assertThat(response.cached()).isFalse();
        verify(cacheRepository, never()).findValid(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cacheHitReflectsCurrentConsentFallbackAndSkipsProviderCall() {
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(
                new StubProvider("LOCAL"), "LOCAL", SummaryFallbackReason.NO_CONSENT);
        SummaryGeneration previouslyGenerated = generation("LOCAL", "Stored output", null);
        SummaryCacheEntry entry = cacheEntry(previouslyGenerated, "LOCAL");
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, false, 0L, null));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(entry));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isTrue();
        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("NO_CONSENT");
        verify(providerSelector, never()).generate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void fallbackOutputUsesActualLocalProviderCacheKey() {
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration generated = generation("LOCAL", "Fallback output", SummaryFallbackReason.LLM_TIMEOUT);
        SummaryCacheEntry entry = cacheEntry(generated, "LOCAL");
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LOCAL")),
                eq("LOCAL"), eq(SummaryLanguage.EN), eq(generated)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(entry, true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("LLM_TIMEOUT");
        verify(cacheRepository).saveIfAbsent(eq(userId), eq(conversationId), eq(window),
                eq(hash("LOCAL")), eq("LOCAL"), eq(SummaryLanguage.EN), eq(generated));
    }

    @Test
    void invalidTimeZoneIsRejectedBeforeAnyRateLimitTokenIsSpent() {
        SummaryRequest request = new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.EN, false, "Mars/Phobos");

        assertThatThrownBy(() -> service.summarize(conversationId, userId, request))
                .isInstanceOf(SummaryInvalidTimeZoneException.class);
        verify(rateLimiter, never()).consume(userId);
        verify(windowService, never()).load(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void notEnoughMessagesReportsTheChosenScopeAndCountWithoutWidening() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-11-17T05:00:00Z"), null);
        SummaryRequest request = new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.EN, false, null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window(conversationId, 3), false, 0L, period));

        assertThatThrownBy(() -> service.summarize(conversationId, userId, request))
                .isInstanceOfSatisfying(SummaryTooFewMessagesException.class, ex -> {
                    assertThat(ex.scope()).isEqualTo(SummaryScope.TODAY);
                    assertThat(ex.available()).isEqualTo(3);
                    assertThat(ex.required()).isEqualTo(5);
                    assertThat(ex.period()).isEqualTo(period);
                });
        // Exactly one window read: the service never retries with a broader range.
        verify(windowService, org.mockito.Mockito.times(1)).load(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void contentHashNamesScopeCountDayZoneAndConsentGeneration() {
        SummaryPeriod today = SummaryPeriods.today(Instant.parse("2026-11-17T05:00:00Z"), "Asia/Ho_Chi_Minh");
        SummaryPeriod nextDay = SummaryPeriods.today(Instant.parse("2026-11-18T05:00:00Z"), "Asia/Ho_Chi_Minh");
        SummaryPeriod otherZone = SummaryPeriods.today(Instant.parse("2026-11-17T05:00:00Z"), "UTC");
        SummaryRequest todayRequest = new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.EN, false);
        SummaryRequest lastN50 = new SummaryRequest(SummaryScope.LAST_N, 50, SummaryLanguage.EN, false);
        SummaryRequest lastN20 = new SummaryRequest(SummaryScope.LAST_N, 20, SummaryLanguage.EN, false);
        SummaryRequest unread = new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, false);

        String base = SummaryService.contentHash(window, todayRequest, today, 1L);

        assertThat(base).isEqualTo(SummaryService.contentHash(window, todayRequest, today, 1L));
        assertThat(base).isNotEqualTo(SummaryService.contentHash(window, todayRequest, nextDay, 1L));
        assertThat(base).isNotEqualTo(SummaryService.contentHash(window, todayRequest, otherZone, 1L));
        assertThat(base).isNotEqualTo(SummaryService.contentHash(window, todayRequest, today, 2L));
        assertThat(base).isNotEqualTo(SummaryService.contentHash(window, unread, null, 1L));
        assertThat(SummaryService.contentHash(window, lastN50, null, 1L))
                .isNotEqualTo(SummaryService.contentHash(window, lastN20, null, 1L))
                .isNotEqualTo(SummaryService.contentHash(window, unread, null, 1L));
    }

    @Test
    void cachedSummaryCitingAMessageOutsideTheWindowIsNeverReplayed() {
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LOCAL"), "LOCAL", null);
        UUID inside = window.messages().get(0).id();
        SummaryGeneration poisoned = new SummaryGeneration(
                new SummaryContent("Poisoned", List.of(new SummaryItem("Cites a stranger", List.of(UUID.randomUUID()))),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, null);
        SummaryGeneration fresh = new SummaryGeneration(
                new SummaryContent("Fresh", List.of(new SummaryItem("Cites inside", List.of(inside))),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, false, 0L, null));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(cacheEntry(poisoned, "LOCAL")))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(fresh);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LOCAL")),
                eq("LOCAL"), eq(SummaryLanguage.EN), eq(fresh)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(fresh, "LOCAL"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.overview()).isEqualTo("Fresh");
        assertThat(response.cached()).isFalse();
        verify(cacheRepository).delete(conversationId, window.fromSeq(), window.toSeq(), hash("LOCAL"), "LOCAL",
                SummaryLanguage.EN);
    }

    @Test
    void responseNamesScopePeriodAndTheProcessorThatActuallyRan() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-11-17T05:00:00Z"), null);
        SummaryRequest request = new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.EN, false, null);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        // The external provider failed, so the local engine answered: the label must say so.
        SummaryGeneration generated = generation("LOCAL", "Local answer", SummaryFallbackReason.LLM_TIMEOUT);
        SummaryReadContext context = new SummaryReadContext(window, true, 4L, period);
        String hash = SummaryService.contentHash(window, request, period, 4L);
        when(windowService.load(conversationId, userId, request)).thenReturn(context);
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(), hash, "LLM",
                SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        String localHash = SummaryService.contentHash(window, request, period, -1L);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(localHash), eq("LOCAL"),
                eq(SummaryLanguage.EN), eq(generated)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(generated, "LOCAL"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.scope()).isEqualTo(SummaryScope.TODAY);
        assertThat(response.limit()).isNull();
        assertThat(response.period()).isEqualTo(period);
        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("LLM_TIMEOUT");
    }

    @Test
    void lastNResponseEchoesTheRequestedCountAndNoPeriod() {
        SummaryRequest request = new SummaryRequest(SummaryScope.LAST_N, 20, SummaryLanguage.EN, false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LOCAL"), "LOCAL", null);
        SummaryGeneration generated = generation("LOCAL", "Local", null);
        String hash = SummaryService.contentHash(window, request, null, -1L);
        when(windowService.load(conversationId, userId, request)).thenReturn(new SummaryReadContext(window, false, 0L, null));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(), hash, "LOCAL",
                SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash), eq("LOCAL"),
                eq(SummaryLanguage.EN), eq(generated)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(generated, "LOCAL"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.scope()).isEqualTo(SummaryScope.LAST_N);
        assertThat(response.limit()).isEqualTo(20);
        assertThat(response.period()).isNull();
    }

    @Test
    void identicalConcurrentRequestsCallTheProviderOnceAndReplayTheWinnersRow() throws Exception {
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration generated = generation("LLM", "Shared output", null);
        SummaryCacheEntry winnerEntry = cacheEntry(generated, "LLM");
        AtomicReference<Optional<SummaryCacheEntry>> stored = new AtomicReference<>(Optional.empty());
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(hash("LLM")), eq("LLM"), eq(SummaryLanguage.EN))).thenAnswer(invocation -> stored.get());
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
            providerStarted.countDown();
            if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("The blocking provider was never released");
            }
            return generated;
        });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(generated))).thenAnswer(invocation -> {
            stored.set(Optional.of(winnerEntry));
            return new SummaryCacheRepository.SavedSummary(winnerEntry, true);
        });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<SummaryResponse>> inFlight = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                inFlight.add(executor.submit(() -> service.summarize(conversationId, userId, request)));
            }
            assertThat(providerStarted.await(5, TimeUnit.SECONDS))
                    .as("the leader should reach the blocked provider")
                    .isTrue();
            releaseProvider.countDown();

            List<SummaryResponse> responses = new ArrayList<>();
            for (Future<SummaryResponse> call : inFlight) {
                responses.add(call.get(15, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(5);
            assertThat(responses).allSatisfy(response -> assertThat(response.summaryId())
                    .isEqualTo(winnerEntry.id()));
            assertThat(responses.stream().filter(SummaryResponse::cached)).hasSize(4);
            assertThat(responses.stream().filter(response -> !response.cached())).hasSize(1);
            verify(providerSelector, times(1)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsOverDifferentWindowsAreNotSerialized() throws Exception {
        SummaryWindow otherWindow = window(conversationId, 5);
        SummaryRequest firstRequest = new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, false);
        SummaryRequest secondRequest = new SummaryRequest(SummaryScope.LAST_N, 20, SummaryLanguage.EN, false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseBoth = new CountDownLatch(1);
        when(windowService.load(conversationId, userId, firstRequest))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(windowService.load(conversationId, userId, secondRequest))
                .thenReturn(new SummaryReadContext(otherWindow, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(any(UUID.class), anyLong(), anyLong(), anyString(), anyString(),
                any(SummaryLanguage.class))).thenReturn(Optional.empty());
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
            SummaryWindow invokedWindow = invocation.getArgument(1);
            CountDownLatch entered = invokedWindow == window ? firstEntered : secondEntered;
            entered.countDown();
            if (!releaseBoth.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("The blocking provider was never released");
            }
            return generation("LLM", invokedWindow == window
                    ? "First window output" : "Second window output", null);
        });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), any(SummaryWindow.class),
                anyString(), eq("LLM"), eq(SummaryLanguage.EN), any(SummaryGeneration.class)))
                .thenAnswer(invocation -> {
                    SummaryWindow invokedWindow = invocation.getArgument(2);
                    SummaryGeneration store = invocation.getArgument(6);
                    SummaryCacheEntry entry = new SummaryCacheEntry(UUID.randomUUID(),
                            invokedWindow.fromSeq(), invokedWindow.toSeq(),
                            invocation.getArgument(3), "LLM", SummaryLanguage.EN,
                            SummaryCachePayload.from(store), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
                    return new SummaryCacheRepository.SavedSummary(entry, true);
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SummaryResponse> first = executor.submit(
                    () -> service.summarize(conversationId, userId, firstRequest));
            Future<SummaryResponse> second = executor.submit(
                    () -> service.summarize(conversationId, userId, secondRequest));

            // Both windows must be generating at the same time: neither request
            // waits for the other's provider call to finish.
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseBoth.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS).overview()).isEqualTo("First window output");
            assertThat(second.get(15, TimeUnit.SECONDS).overview()).isEqualTo("Second window output");
            verify(providerSelector, times(2)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));
        } finally {
            releaseBoth.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aFailedLeaderIsReplacedByOneNewLeaderAndTheWaitersReplayItsRow() throws Exception {
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration secondGeneration = generation("LLM", "Second output", null);
        SummaryCacheEntry secondEntry = cacheEntry(secondGeneration, "LLM");
        AtomicReference<Optional<SummaryCacheEntry>> stored = new AtomicReference<>(Optional.empty());
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch waitersProbed = new CountDownLatch(1);
        CountDownLatch releaseNewLeader = new CountDownLatch(1);
        AtomicInteger generateCalls = new AtomicInteger();
        AtomicInteger waiterProbes = new AtomicInteger();
        AtomicInteger generating = new AtomicInteger();
        AtomicInteger maxGenerating = new AtomicInteger();
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(hash("LLM")), eq("LLM"), eq(SummaryLanguage.EN))).thenAnswer(invocation -> {
                    // Every waiter probes once before joining the flight; when the last
                    // one has probed, the leader is released to fail.
                    if (providerStarted.getCount() == 0 && stored.get().isEmpty()
                            && waiterProbes.incrementAndGet() == 4) {
                        waitersProbed.countDown();
                    }
                    return stored.get();
                });
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
                    if (generateCalls.incrementAndGet() == 1) {
                        providerStarted.countDown();
                        if (!waitersProbed.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("The waiters never probed the cache");
                        }
                        throw new IllegalStateException("The leader provider call failed");
                    }
                    maxGenerating.accumulateAndGet(generating.incrementAndGet(), Math::max);
                    try {
                        if (!releaseNewLeader.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("The new leader was never released");
                        }
                        return secondGeneration;
                    } finally {
                        generating.decrementAndGet();
                    }
                });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), any(SummaryGeneration.class)))
                .thenAnswer(invocation -> {
                    stored.set(Optional.of(secondEntry));
                    return new SummaryCacheRepository.SavedSummary(secondEntry, true);
                });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<SummaryResponse> leader = executor.submit(
                    () -> service.summarize(conversationId, userId, request));
            assertThat(providerStarted.await(5, TimeUnit.SECONDS))
                    .as("the leader should reach the provider")
                    .isTrue();
            List<Future<SummaryResponse>> waiters = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                waiters.add(executor.submit(() -> service.summarize(conversationId, userId, request)));
            }

            // Exactly one waiter must step up as the new leader; the others
            // re-coalesce behind it instead of each calling the provider.
            Thread.sleep(500);
            assertThat(maxGenerating.get())
                    .as("one waiter replaces the failed leader")
                    .isEqualTo(1);
            releaseNewLeader.countDown();

            List<SummaryResponse> responses = new ArrayList<>();
            for (Future<SummaryResponse> waiter : waiters) {
                responses.add(waiter.get(15, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(4);
            assertThat(responses).allSatisfy(response -> assertThat(response.summaryId())
                    .isEqualTo(secondEntry.id()));
            assertThat(responses.stream().filter(SummaryResponse::cached)).hasSize(3);
            // The failed leader plus exactly one re-coalesced leader: two calls total.
            verify(providerSelector, times(2)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));
            assertThatThrownBy(() -> leader.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            releaseNewLeader.countDown();
            executor.shutdownNow();
        }
    }


    @Test
    void aSlowLeaderLetsWaitersAnswerAfterOneSharedWaitBudget() throws Exception {
        // The 250 ms bound is one end-to-end deadline shared by every wait
        // round, not a per-round budget: behind a leader that never finishes,
        // each waiter answers on its own after about one bound (~250 ms), not
        // after three (~750 ms, the per-round behavior).
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), 250L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration leaderGeneration = generation("LLM", "Leader output", null);
        SummaryGeneration ownGeneration = generation("LLM", "Own output", null);
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger generateCalls = new AtomicInteger();
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(hash("LLM")), eq("LLM"), eq(SummaryLanguage.EN))).thenReturn(Optional.empty());
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
                    if (generateCalls.incrementAndGet() == 1) {
                        providerStarted.countDown();
                        // The leader outlives the waiters' whole budget: it
                        // stays blocked until the test lets go, well past
                        // three per-round bounds.
                        if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("The blocking provider was never released");
                        }
                        return leaderGeneration;
                    }
                    // A waiter whose shared budget ran out answers on its own
                    // without another wait.
                    return ownGeneration;
                });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), any(SummaryGeneration.class)))
                .thenAnswer(invocation -> {
                    SummaryGeneration stored = invocation.getArgument(6);
                    SummaryCacheEntry entry = new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(),
                            window.toSeq(), hash("LLM"), "LLM", SummaryLanguage.EN,
                            SummaryCachePayload.from(stored), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
                    return new SummaryCacheRepository.SavedSummary(entry, true);
                });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<SummaryResponse> leader = executor.submit(
                    () -> service.summarize(conversationId, userId, request));
            assertThat(providerStarted.await(5, TimeUnit.SECONDS))
                    .as("the leader should reach the blocked provider")
                    .isTrue();
            List<Future<SummaryResponse>> waiters = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                waiters.add(executor.submit(() -> service.summarize(conversationId, userId, request)));
            }

            List<SummaryResponse> responses = new ArrayList<>();
            for (Future<SummaryResponse> waiter : waiters) {
                // Each waiter parks once on the leader's flight for what is
                // left of the shared deadline, then generates on its own. A
                // per-round budget would keep it parked for ~750 ms; the
                // 600 ms get below only passes near one bound (~250 ms).
                responses.add(waiter.get(600, TimeUnit.MILLISECONDS));
            }
            assertThat(responses).hasSize(4);
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.overview()).isEqualTo("Own output");
                assertThat(response.cached()).isFalse();
            });
            // The leader plus each waiter's own answer: five provider calls.
            verify(providerSelector, times(5)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));

            releaseProvider.countDown();
            assertThat(leader.get(15, TimeUnit.SECONDS).overview()).isEqualTo("Leader output");
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aLeaderThatDiesUnexpectedlyStillWakesTheWaiters() throws Exception {
        // The wait bound is far beyond the response deadline below: waiters can
        // only answer in time if the leader's death itself wakes them.
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), 10_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration secondGeneration = generation("LLM", "Second output", null);
        SummaryCacheEntry secondEntry = cacheEntry(secondGeneration, "LLM");
        AtomicReference<Optional<SummaryCacheEntry>> stored = new AtomicReference<>(Optional.empty());
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch waitersProbed = new CountDownLatch(1);
        AtomicInteger generateCalls = new AtomicInteger();
        AtomicInteger waiterProbes = new AtomicInteger();
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(hash("LLM")), eq("LLM"), eq(SummaryLanguage.EN))).thenAnswer(invocation -> {
                    if (providerStarted.getCount() == 0 && stored.get().isEmpty()
                            && waiterProbes.incrementAndGet() == 4) {
                        waitersProbed.countDown();
                    }
                    return stored.get();
                });
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
                    if (generateCalls.incrementAndGet() == 1) {
                        providerStarted.countDown();
                        if (!waitersProbed.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("The waiters never probed the cache");
                        }
                        // Not a RuntimeException: the leader dies without
                        // completing the flight the usual way.
                        throw new LeaderDiedError();
                    }
                    return secondGeneration;
                });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), any(SummaryGeneration.class)))
                .thenAnswer(invocation -> {
                    stored.set(Optional.of(secondEntry));
                    return new SummaryCacheRepository.SavedSummary(secondEntry, true);
                });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Future<SummaryResponse> leader = executor.submit(
                    () -> service.summarize(conversationId, userId, request));
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            List<Future<SummaryResponse>> waiters = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                waiters.add(executor.submit(() -> service.summarize(conversationId, userId, request)));
            }

            List<SummaryResponse> responses = new ArrayList<>();
            for (Future<SummaryResponse> waiter : waiters) {
                // The waiters must wake from the leader's death well before the
                // wait bound: none of them may sit out the full bound.
                responses.add(waiter.get(2, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(4);
            assertThat(responses).allSatisfy(response -> assertThat(response.summaryId())
                    .isEqualTo(secondEntry.id()));
            verify(providerSelector, times(2)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));
            assertThatThrownBy(() -> leader.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LeaderDiedError.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anInterruptedWaiterStopsInsteadOfGeneratingOnItsOwn() throws Exception {
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration generated = generation("LLM", "Shared output", null);
        SummaryCacheEntry winnerEntry = cacheEntry(generated, "LLM");
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch waiterProbed = new CountDownLatch(1);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(hash("LLM")), eq("LLM"), eq(SummaryLanguage.EN))).thenAnswer(invocation -> {
                    // The first probe after the provider started is the waiter
                    // locking onto the leader's flight.
                    if (providerStarted.getCount() == 0) {
                        waiterProbed.countDown();
                    }
                    return Optional.empty();
                });
        when(providerSelector.generate(any(PreparedSummaryProvider.class), any(SummaryWindow.class),
                any(SummaryLanguage.class))).thenAnswer(invocation -> {
                    providerStarted.countDown();
                    if (!releaseProvider.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("The blocking provider was never released");
                    }
                    return generated;
                });
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(generated)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(winnerEntry, true));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<SummaryResponse> leader = executor.submit(
                    () -> service.summarize(conversationId, userId, request));
            assertThat(providerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    service.summarize(conversationId, userId, request);
                } catch (RuntimeException | Error ex) {
                    failure.set(ex);
                }
            });
            waiter.start();
            assertThat(waiterProbed.await(5, TimeUnit.SECONDS))
                    .as("the waiter should lock onto the leader's flight")
                    .isTrue();
            // The waiter is parked on the leader's flight: cancelling it must
            // answer, not spend a provider call of its own.
            waiter.interrupt();
            waiter.join(5_000);
            releaseProvider.countDown();

            assertThat(failure.get()).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR));
            assertThat(waiter.isInterrupted())
                    .as("the interrupt flag must be restored on the request thread")
                    .isTrue();
            // The waiter must not have spent its own provider call; only the
            // leader's call ever ran.
            assertThat(leader.get(15, TimeUnit.SECONDS).summaryId()).isEqualTo(winnerEntry.id());
            verify(providerSelector, times(1)).generate(any(PreparedSummaryProvider.class),
                    any(SummaryWindow.class), any(SummaryLanguage.class));
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    /** Stands in for a leader that dies with an error that is not a RuntimeException. */
    private static final class LeaderDiedError extends Error {
    }

    @Test
    void aStoredTransientProviderFallbackIsReplayedInsideTheCircuitWindowWithoutRecallingTheProvider() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        SettableClock clock = new SettableClock(now);
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), clock, 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryContent fallbackContent = new SummaryContent("Stored fallback output",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        SummaryGeneration fallback = new SummaryGeneration(fallbackContent, SummaryLanguage.EN,
                "LOCAL", "extractive-engine", SummaryFallbackReason.LLM_TIMEOUT, null);
        SummaryCacheEntry entry = cacheEntry(fallback, "LOCAL", now);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN)).thenReturn(Optional.of(entry));
        // Still inside the breaker window: the failing external tier must not
        // be retried yet.
        clock.set(now.plus(CircuitBreaker.OPEN_DURATION).minusSeconds(1));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isTrue();
        assertThat(response.overview()).isEqualTo("Stored fallback output");
        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("LLM_TIMEOUT");
        // The replayed row is described by what the row itself recorded, not
        // by what this request looked like.
        assertThat(response.model()).isEqualTo("extractive-engine");
        verify(providerSelector, never()).generate(any(PreparedSummaryProvider.class),
                any(SummaryWindow.class), any(SummaryLanguage.class));
    }

    @Test
    void aConcurrentWinnerRowSetsTheReportedReasonAndModel() {
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        // This request fell back with a timeout, but a concurrent winner already
        // stored the same window with a different failure recorded.
        SummaryGeneration ownFallback = generation("LOCAL", "Ignored own output", SummaryFallbackReason.LLM_TIMEOUT);
        SummaryContent winnerContent = new SummaryContent("Winner fallback output",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        SummaryGeneration winner = new SummaryGeneration(winnerContent, SummaryLanguage.EN,
                "LOCAL", "extractive-engine", SummaryFallbackReason.LLM_HTTP_ERROR, null);
        SummaryCacheEntry winnerEntry = cacheEntry(winner, "LOCAL");
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(ownFallback);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LOCAL")),
                eq("LOCAL"), eq(SummaryLanguage.EN), eq(ownFallback)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(winnerEntry, false));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isTrue();
        // The stored row's labels win, not this request's own fallback.
        assertThat(response.overview()).isEqualTo("Winner fallback output");
        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("LLM_HTTP_ERROR");
        assertThat(response.model()).isEqualTo("extractive-engine");
    }

    @Test
    void aStoredTransientProviderFallbackIsRetriedAfterTheCircuitWindow() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        SettableClock clock = new SettableClock(now);
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), clock, 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration fallback = generation("LOCAL", "Stored fallback output", SummaryFallbackReason.LLM_TIMEOUT);
        SummaryGeneration fresh = generation("LLM", "Fresh external output", null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(cacheEntry(fallback, "LOCAL", now)));
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(fresh);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(fresh)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(fresh, "LLM"), true));
        // The breaker window has elapsed: the external tier is retried instead
        // of pinning the local answer to it.
        clock.set(now.plus(CircuitBreaker.OPEN_DURATION).plusSeconds(1));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isFalse();
        assertThat(response.overview()).isEqualTo("Fresh external output");
        assertThat(response.generatedBy()).isEqualTo("LLM");
        verify(providerSelector, times(1)).generate(prepared, window, SummaryLanguage.EN);
    }

    @Test
    void aFallbackRowThatFailsTheReplayGuardsIsDeleted() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        SettableClock clock = new SettableClock(now);
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), clock, 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        UUID inside = window.messages().get(0).id();
        // The stored fallback cites a message outside the window: replaying it
        // would be wrong and keeping it would re-probe it on every request.
        SummaryGeneration stale = new SummaryGeneration(
                new SummaryContent("Stale fallback", List.of(
                        new SummaryItem("Cites a stranger", List.of(UUID.randomUUID()))),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, SummaryFallbackReason.LLM_TIMEOUT, null);
        SummaryGeneration fresh = new SummaryGeneration(
                new SummaryContent("Fresh", List.of(new SummaryItem("Cites inside", List.of(inside))),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, SummaryFallbackReason.LLM_TIMEOUT, null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(cacheEntry(stale, "LOCAL", now)))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(fresh);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LOCAL")),
                eq("LOCAL"), eq(SummaryLanguage.EN), eq(fresh)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(fresh, "LOCAL"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.overview()).isEqualTo("Fresh");
        assertThat(response.cached()).isFalse();
        verify(cacheRepository).delete(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN);
    }

    @Test
    void aConsentDowngradeRowIsNotReplayedToAConsentedExternalRequest() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        SettableClock clock = new SettableClock(now);
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), clock, 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration downgraded = generation("LOCAL", "Downgraded output", SummaryFallbackReason.NO_CONSENT);
        SummaryGeneration fresh = generation("LLM", "Fresh external output", null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        // The row is young enough to replay; only its recorded reason keeps it out.
        lenient().when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(cacheEntry(downgraded, "LOCAL", now)));
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(fresh);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(fresh)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(fresh, "LLM"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isFalse();
        assertThat(response.overview()).isEqualTo("Fresh external output");
        assertThat(response.generatedBy()).isEqualTo("LLM");
    }

    @Test
    void aRoutingDowngradeRowWithoutAFallbackReasonIsNotReplayed() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        SettableClock clock = new SettableClock(now);
        service = new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository,
                ConversationAiOptOuts.none(), clock, 1_000L);
        SummaryRequest request = request(false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider("LLM"), "LLM", null);
        SummaryGeneration downgrade = generation("LOCAL", "Routine local output", null);
        SummaryGeneration fresh = generation("LLM", "Fresh external output", null);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LLM"), "LLM", SummaryLanguage.EN)).thenReturn(Optional.empty());
        // The row is young enough to replay; only its missing reason keeps it out.
        lenient().when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN))
                .thenReturn(Optional.of(cacheEntry(downgrade, "LOCAL", now)));
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(fresh);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window), eq(hash("LLM")),
                eq("LLM"), eq(SummaryLanguage.EN), eq(fresh)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(cacheEntry(fresh, "LLM"), true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.cached()).isFalse();
        assertThat(response.overview()).isEqualTo("Fresh external output");
        assertThat(response.generatedBy()).isEqualTo("LLM");
    }

    private String hash(String provider) {
        return SummaryService.contentHash(window, request(false), null, "LLM".equals(provider) ? 0L : -1L);
    }

    private SummaryRequest request(boolean refresh) {
        return new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, refresh);
    }

    private SummaryCacheEntry cacheEntry(SummaryGeneration generation, String provider) {
        return cacheEntry(generation, provider, Instant.EPOCH);
    }

    private SummaryCacheEntry cacheEntry(SummaryGeneration generation, String provider, Instant createdAt) {
        return new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                hash(provider), provider, SummaryLanguage.EN,
                SummaryCachePayload.from(generation), createdAt, createdAt.plusSeconds(86400));
    }

    /** A clock the test moves by hand to sit before and after a cache row's window. */
    private static final class SettableClock extends Clock {

        private Instant instant;

        private SettableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static SummaryGeneration generation(String provider, String overview, SummaryFallbackReason reason) {
        return new SummaryGeneration(new SummaryContent(overview, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()), SummaryLanguage.EN, provider, null, reason);
    }

    private static SummaryWindow window(UUID conversationId, int count) {
        UUID senderId = UUID.randomUUID();
        return new SummaryWindow(conversationId, 0, 0,
                java.util.stream.IntStream.rangeClosed(1, count)
                        .mapToObj(seq -> new SummaryMessage(UUID.randomUUID(), seq, senderId, "Sender",
                                "Message " + seq, Instant.EPOCH, null))
                        .toList());
    }

    private static final class StubProvider implements SummaryProvider {

        private final String id;

        private StubProvider(String id) {
            this.id = id;
        }

        @Override
        public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
            return new ProviderResult(
                    new SummaryContent("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                    SummaryLanguage.EN,
                    null);
        }

        @Override
        public String id() {
            return id;
        }
    }
}
