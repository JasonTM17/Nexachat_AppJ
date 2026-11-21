package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The cache-lookup path applies the opt-out replay guard, and the concurrent
 * cache-fill path must apply it to the winner of {@code saveIfAbsent} too: a
 * summary generated just before an opt-out must never be served to a request
 * that already observed the opt-out.
 */
@ExtendWith(MockitoExtension.class)
class SummaryCacheWinnerOptOutTest {

    @Mock
    private SummaryRateLimiter rateLimiter;
    @Mock
    private SummaryWindowService windowService;
    @Mock
    private SummaryProviderSelector providerSelector;
    @Mock
    private SummaryCacheRepository cacheRepository;
    @Mock
    private ConversationAiOptOuts aiOptOuts;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final UUID optedOutSender = UUID.randomUUID();
    private final UUID safeSender = UUID.randomUUID();
    private final SummaryWindow window = window();

    @Test
    void directCacheRejectsChangedOptOutEvenWhenSecretOverviewHasOnlySafeCitations() {
        SummaryRequest request = request();
        configure(request);
        // The collaborator can return a mutable collection: the read snapshot must be copied.
        Set<UUID> exclusions = new HashSet<>();
        when(aiOptOuts.optedOutUserIds(conversationId)).thenReturn(exclusions);
        SummaryCacheEntry stale = entry(scopedHash(request), external("The secret value is alpha-bravo"));
        assertThat(SummaryService.replayAllowed(stale, window, Set.of(optedOutSender))).isTrue();
        when(cacheRepository.findValid(eq(conversationId), anyLong(), anyLong(), anyString(), eq("LLM"), any()))
                .thenAnswer(invocation -> {
                    exclusions.add(optedOutSender);
                    return Optional.of(stale);
                });

        assertConsentSuppressed(() -> service().summarize(conversationId, userId, request));
        verify(providerSelector, never()).generate(any(), any(), any());
    }

    @Test
    void externalGenerationIsNotStoredWhenUncitedOverviewContextChanges() {
        SummaryRequest request = request();
        PreparedSummaryProvider prepared = configure(request);
        AtomicReference<Set<UUID>> exclusions = new AtomicReference<>(Set.of());
        when(aiOptOuts.optedOutUserIds(conversationId)).thenAnswer(invocation -> exclusions.get());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenAnswer(invocation -> {
            exclusions.set(Set.of(optedOutSender));
            return external("The secret value is alpha-bravo");
        });
        lenient().when(cacheRepository.saveIfAbsent(any(), any(), any(), anyString(), eq("LLM"), any(), any()))
                .thenAnswer(invocation -> new SummaryCacheRepository.SavedSummary(
                        entry(invocation.getArgument(3), invocation.getArgument(6)), true));

        assertConsentSuppressed(() -> service().summarize(conversationId, userId, request));
        verify(cacheRepository, never()).saveIfAbsent(any(), any(), any(), anyString(), anyString(), any(), any());
        verify(cacheRepository, never()).replace(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void changedOptOutSuppressesAnUncitedConcurrentWinner() {
        SummaryRequest request = request();
        PreparedSummaryProvider prepared = configure(request);
        AtomicReference<Set<UUID>> exclusions = new AtomicReference<>(Set.of());
        when(aiOptOuts.optedOutUserIds(conversationId)).thenAnswer(invocation -> exclusions.get());
        SummaryGeneration safe = external("Safe regeneration");
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(safe);
        when(cacheRepository.saveIfAbsent(any(), any(), any(), anyString(), eq("LLM"), any(), eq(safe)))
                .thenAnswer(invocation -> {
                    exclusions.set(Set.of(optedOutSender));
                    return new SummaryCacheRepository.SavedSummary(
                            entry(invocation.getArgument(3), external("The secret value is alpha-bravo")), false);
                });

        assertConsentSuppressed(() -> service().summarize(conversationId, userId, request));
    }

    @Test
    void waiterRejectsUncitedCacheAfterOptOutMovesWhileParked() throws Exception {
        SummaryRequest request = request();
        configure(request);
        AtomicReference<Set<UUID>> exclusions = new AtomicReference<>(Set.of());
        AtomicReference<SummaryCacheEntry> cached = new AtomicReference<>();
        when(aiOptOuts.optedOutUserIds(conversationId)).thenAnswer(invocation -> exclusions.get());
        CountDownLatch probed = new CountDownLatch(1);
        when(cacheRepository.findValid(eq(conversationId), anyLong(), anyLong(), anyString(), eq("LLM"), any()))
                .thenAnswer(invocation -> {
                    probed.countDown();
                    return Optional.ofNullable(cached.get());
                });
        SummaryService service = service();
        var field = SummaryService.class.getDeclaredField("singleFlights");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var flights = (ConcurrentHashMap<String, CompletableFuture<Void>>) field.get(service);
        String key = conversationId + "|" + window.fromSeq() + "|" + window.toSeq() + "|"
                + scopedHash(request) + "|LLM|EN";
        CompletableFuture<Void> leader = new CompletableFuture<>();
        flights.put(key, leader);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                service.summarize(conversationId, userId, request);
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        try {
            waiter.start();
            assertThat(probed.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(waiter.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            exclusions.set(Set.of(optedOutSender));
            cached.set(entry(scopedHash(request), external("The secret value is alpha-bravo")));
            flights.remove(key, leader);
            leader.complete(null);
            waiter.join(5_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(ApiException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED));
            verify(providerSelector, never()).generate(any(), any(), any());
        } finally {
            leader.complete(null);
            waiter.interrupt();
            waiter.join(5_000);
        }
    }

    @Test
    void lateOldFillCannotReplayUnderNewExclusionsAndUnchangedExclusionsStillHitCache() {
        SummaryRequest request = request();
        PreparedSummaryProvider prepared = configure(request);
        AtomicReference<Set<UUID>> exclusions = new AtomicReference<>(Set.of());
        when(aiOptOuts.optedOutUserIds(conversationId)).thenAnswer(invocation -> exclusions.get());
        var rows = new ConcurrentHashMap<String, SummaryCacheEntry>();
        when(cacheRepository.findValid(eq(conversationId), anyLong(), anyLong(), anyString(), eq("LLM"), any()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(3))));
        SummaryGeneration stale = external("The secret value is alpha-bravo");
        SummaryGeneration safe = external("Safe current context");
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(stale, safe);
        AtomicReference<String> oldHash = new AtomicReference<>();
        AtomicReference<String> newHash = new AtomicReference<>();
        when(cacheRepository.saveIfAbsent(any(), any(), any(), anyString(), eq("LLM"), any(), any()))
                .thenAnswer(invocation -> {
                    String hash = invocation.getArgument(3);
                    SummaryGeneration generated = invocation.getArgument(6);
                    SummaryCacheEntry row = entry(hash, generated);
                    rows.put(hash, row);
                    if (generated == stale) {
                        // The old fill lands after the opt-out's purge. The new key must isolate it.
                        exclusions.set(Set.of(optedOutSender));
                        oldHash.set(hash);
                    } else {
                        newHash.set(hash);
                    }
                    return new SummaryCacheRepository.SavedSummary(row, true);
                });
        SummaryService service = service();

        assertConsentSuppressed(() -> service.summarize(conversationId, userId, request));
        SummaryResponse current = service.summarize(conversationId, userId, request);
        SummaryResponse replayed = service.summarize(conversationId, userId, request);

        assertThat(newHash.get()).isNotEqualTo(oldHash.get());
        assertThat(current.overview()).isEqualTo("Safe current context");
        assertThat(current.cached()).isFalse();
        assertThat(current.contextOmitted()).isTrue();
        assertThat(replayed.cached()).isTrue();
        assertThat(replayed.overview()).isEqualTo("Safe current context");
        verify(providerSelector, times(2)).generate(prepared, window, SummaryLanguage.EN);
    }

    private SummaryRequest request() {
        return new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, false);
    }

    private PreparedSummaryProvider configure(SummaryRequest request) {
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider(), "LLM", null);
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        return prepared;
    }

    private SummaryService service() {
        return new SummaryService(rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts);
    }

    private SummaryGeneration external(String overview) {
        // Only the overview contains the other author's text; structured provenance is deliberately safe.
        return new SummaryGeneration(new SummaryContent(overview,
                List.of(new SummaryItem("Safe fact", List.of(window.messages().get(1).id()))),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new SummaryParticipant(safeSender, "Safe", 4))),
                SummaryLanguage.EN, "LLM", null, null);
    }

    private SummaryCacheEntry entry(String hash, SummaryGeneration generation) {
        return new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(), hash,
                "LLM", SummaryLanguage.EN, SummaryCachePayload.from(generation),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
    }

    private static void assertConsentSuppressed(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.AI_CONSENT_REQUIRED));
    }

    @Test
    void concurrentCacheWinnerMustAlsoPassTheOptOutReplayGuard() {
        SummaryRequest request = new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, false);
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(new StubProvider(), "LLM", null);
        SummaryGeneration safe = new SummaryGeneration(
                new SummaryContent("Safe regeneration", List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LLM", null, null);
        SummaryGeneration unsafe = new SummaryGeneration(
                new SummaryContent("The secret value is alpha-bravo", List.of(), List.of(), List.of(),
                        List.of(), List.of(),
                        List.of(new SummaryParticipant(optedOutSender, "Opted out", 1))),
                SummaryLanguage.EN, "LLM", null, null);
        SummaryCacheEntry unsafeWinner = new SummaryCacheEntry(
                UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                scopedHash(request, Set.of(optedOutSender)), "LLM", SummaryLanguage.EN,
                SummaryCachePayload.from(unsafe), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        SummaryCacheEntry safeReplacement = new SummaryCacheEntry(
                UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                scopedHash(request, Set.of(optedOutSender)), "LLM", SummaryLanguage.EN,
                SummaryCachePayload.from(safe), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true, 0L, null));
        when(aiOptOuts.optedOutUserIds(conversationId)).thenReturn(Set.of(optedOutSender));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(scopedHash(request, Set.of(optedOutSender))), eq("LLM"), eq(SummaryLanguage.EN)))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(safe);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window),
                eq(scopedHash(request, Set.of(optedOutSender))), eq("LLM"), eq(SummaryLanguage.EN), eq(safe)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(unsafeWinner, false));
        when(cacheRepository.replace(eq(userId), eq(conversationId), eq(window),
                eq(scopedHash(request, Set.of(optedOutSender))), eq("LLM"), eq(SummaryLanguage.EN), eq(safe)))
                .thenReturn(safeReplacement);

        SummaryService service = new SummaryService(
                rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts);
        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.overview())
                .as("a cache row that fails replayAllowed must never be returned")
                .doesNotContain("alpha-bravo");
    }

    private String scopedHash(SummaryRequest request) {
        return SummaryService.contentHash(window, request, null, 0L);
    }

    private String scopedHash(SummaryRequest request, Set<UUID> optedOut) {
        return SummaryService.contentHash(window, request, null, 0L, optedOut);
    }

    private SummaryWindow window() {
        List<SummaryMessage> messages = List.of(
                new SummaryMessage(UUID.randomUUID(), 1, optedOutSender, "Opted out",
                        "The secret value is alpha-bravo.", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 2, safeSender, "Safe",
                        "The budget was approved.", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 3, safeSender, "Safe",
                        "The release is on Friday.", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 4, safeSender, "Safe",
                        "The review is complete.", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 5, safeSender, "Safe",
                        "The rollout starts next week.", Instant.EPOCH, null));
        return new SummaryWindow(conversationId, 1, 5, messages);
    }

    private static final class StubProvider implements SummaryProvider {
        @Override
        public ProviderResult summarize(SummaryWindow window, SummaryLanguage language) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String id() {
            return "LLM";
        }
    }
}
