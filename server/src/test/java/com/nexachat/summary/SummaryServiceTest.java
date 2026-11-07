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
    void tooFewUsableMessagesReturnsTypedDomainErrorBeforeProviderSelection() {
        SummaryWindow shortWindow = window(conversationId, 4);
        SummaryRequest request = request(false);
        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(shortWindow, false));

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
                .thenReturn(new SummaryReadContext(window, true));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        when(cacheRepository.replace(eq(conversationId), eq(window), eq(SummaryService.contentHash(window)),
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
                .thenReturn(new SummaryReadContext(window, false));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                SummaryService.contentHash(window), "LOCAL", SummaryLanguage.EN))
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
                .thenReturn(new SummaryReadContext(window, true));
        when(providerSelector.prepare(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(conversationId, window.fromSeq(), window.toSeq(),
                SummaryService.contentHash(window), "LLM", SummaryLanguage.EN))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(generated);
        when(cacheRepository.saveIfAbsent(eq(conversationId), eq(window), eq(SummaryService.contentHash(window)),
                eq("LOCAL"), eq(SummaryLanguage.EN), eq(generated)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(entry, true));

        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.generatedBy()).isEqualTo("LOCAL");
        assertThat(response.fallbackReason()).isEqualTo("LLM_TIMEOUT");
        verify(cacheRepository).saveIfAbsent(eq(conversationId), eq(window),
                eq(SummaryService.contentHash(window)), eq("LOCAL"), eq(SummaryLanguage.EN), eq(generated));
    }

    private SummaryRequest request(boolean refresh) {
        return new SummaryRequest(SummaryScope.LAST_N, 5, SummaryLanguage.EN, refresh);
    }

    private SummaryCacheEntry cacheEntry(SummaryGeneration generation, String provider) {
        return new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                SummaryService.contentHash(window), provider, SummaryLanguage.EN,
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
