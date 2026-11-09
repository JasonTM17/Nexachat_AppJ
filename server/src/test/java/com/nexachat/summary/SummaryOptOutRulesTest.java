package com.nexachat.summary;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryOptOutRulesTest {

    private final UUID conversationId = UUID.randomUUID();
    private final UUID optedOutSender = UUID.randomUUID();
    private final UUID safeSender = UUID.randomUUID();
    private final UUID optedOutMessage = UUID.randomUUID();
    private final UUID safeMessage = UUID.randomUUID();
    private final SummaryWindow window = new SummaryWindow(conversationId, 0, 0, List.of(
            new SummaryMessage(optedOutMessage, 1, optedOutSender, "Opted out",
                    "The private detail.", Instant.EPOCH, null),
            new SummaryMessage(safeMessage, 2, safeSender, "Safe",
                    "The public detail.", Instant.EPOCH, null)));

    @Test
    void cachedExternalSummaryCitingAnOptedOutAuthorIsNotReplayable() {
        SummaryCacheEntry entry = entry("LLM", List.of(optedOutMessage), List.of(optedOutSender));

        assertThat(SummaryService.replayAllowed(entry, window, Set.of(optedOutSender))).isFalse();
    }

    @Test
    void cachedExternalSummaryCitingASafeAuthorIsReplayable() {
        SummaryCacheEntry entry = entry("LLM", List.of(safeMessage), List.of(safeSender));

        assertThat(SummaryService.replayAllowed(entry, window, Set.of(optedOutSender))).isTrue();
    }

    @Test
    void cachedLocalSummaryMayStillCiteAnOptedOutAuthor() {
        SummaryCacheEntry entry = entry("LOCAL", List.of(optedOutMessage), List.of(optedOutSender));

        assertThat(SummaryService.replayAllowed(entry, window, Set.of(optedOutSender))).isTrue();
    }

    @Test
    void externalSummaryFlagsOmittedContextWhileLocalDoesNot() {
        assertThat(SummaryService.contextOmitted(
                generation("LLM"), window, Set.of(optedOutSender))).isTrue();
        assertThat(SummaryService.contextOmitted(
                generation("LOCAL"), window, Set.of(optedOutSender))).isFalse();
        assertThat(SummaryService.contextOmitted(
                generation("LLM"), window, Set.of())).isFalse();
    }

    private SummaryCacheEntry entry(String generatedBy, List<UUID> citedIds, List<UUID> participantIds) {
        SummaryGeneration generation = new SummaryGeneration(
                new SummaryContent(
                        "Overview",
                        List.of(new SummaryItem("Key point", citedIds)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        participantIds.stream()
                                .map(id -> new SummaryParticipant(id, "Name", 1))
                                .toList()),
                SummaryLanguage.EN,
                generatedBy,
                null,
                null);
        return new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                "hash", generatedBy, SummaryLanguage.EN, SummaryCachePayload.from(generation),
                Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
    }

    private static SummaryGeneration generation(String generatedBy) {
        return new SummaryGeneration(
                new SummaryContent("Overview", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, generatedBy, null, null);
    }
}
