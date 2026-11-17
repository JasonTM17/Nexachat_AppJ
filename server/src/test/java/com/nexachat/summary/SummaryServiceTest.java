package com.nexachat.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
                hash("LOCAL"), "LOCAL", SummaryLanguage.EN)).thenReturn(Optional.of(cacheEntry(poisoned, "LOCAL")));
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

    private String hash(String provider) {
        return SummaryService.contentHash(window, request(false), null, "LLM".equals(provider) ? 0L : -1L);
    }

    private SummaryRequest request(boolean refresh) {
        return new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, refresh);
    }

    private SummaryCacheEntry cacheEntry(SummaryGeneration generation, String provider) {
        return new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                hash(provider), provider, SummaryLanguage.EN,
                SummaryCachePayload.from(generation), Instant.EPOCH, Instant.EPOCH.plusSeconds(86400));
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
