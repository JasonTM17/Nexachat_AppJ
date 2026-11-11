package com.nexachat.summary;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
                SummaryService.contentHash(window), "LLM", SummaryLanguage.EN,
                SummaryCachePayload.from(unsafe), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        SummaryCacheEntry safeReplacement = new SummaryCacheEntry(
                UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                SummaryService.contentHash(window), "LLM", SummaryLanguage.EN,
                SummaryCachePayload.from(safe), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));

        when(windowService.load(conversationId, userId, request))
                .thenReturn(new SummaryReadContext(window, true));
        when(aiOptOuts.optedOutUserIds(conversationId)).thenReturn(Set.of(optedOutSender));
        when(providerSelector.prepare(anyBoolean(), any(), anyBoolean())).thenReturn(prepared);
        when(cacheRepository.findValid(eq(conversationId), eq(window.fromSeq()), eq(window.toSeq()),
                eq(SummaryService.contentHash(window)), eq("LLM"), eq(SummaryLanguage.EN)))
                .thenReturn(Optional.empty());
        when(providerSelector.generate(prepared, window, SummaryLanguage.EN)).thenReturn(safe);
        when(cacheRepository.saveIfAbsent(eq(userId), eq(conversationId), eq(window),
                eq(SummaryService.contentHash(window)), eq("LLM"), eq(SummaryLanguage.EN), eq(safe)))
                .thenReturn(new SummaryCacheRepository.SavedSummary(unsafeWinner, false));
        when(cacheRepository.replace(eq(userId), eq(conversationId), eq(window),
                eq(SummaryService.contentHash(window)), eq("LLM"), eq(SummaryLanguage.EN), eq(safe)))
                .thenReturn(safeReplacement);

        SummaryService service = new SummaryService(
                rateLimiter, windowService, providerSelector, cacheRepository, aiOptOuts);
        SummaryResponse response = service.summarize(conversationId, userId, request);

        assertThat(response.overview())
                .as("a cache row that fails replayAllowed must never be returned")
                .doesNotContain("alpha-bravo");
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
