package com.nexachat.summary.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicExtractorTest {

    @Test
    void zeroLimitOrEmptyCandidatesProduceNothing() {
        SummaryCandidate candidate = candidate();
        assertTrue(TopicExtractor.extract(List.of(candidate), SummaryLanguage.EN, 0).isEmpty());
        assertTrue(TopicExtractor.extract(List.of(), SummaryLanguage.VI, 3).isEmpty());
    }

    @Test
    void stopWordsAreDroppedAndContentPhrasesStayInOrder() {
        List<String> topics = TopicExtractor.extract(List.of(candidate()), SummaryLanguage.EN, 2);

        assertFalse(topics.isEmpty());
        assertTrue(topics.stream().noneMatch(topic -> topic.equalsIgnoreCase("the")));
        assertTrue(topics.get(0).toLowerCase(java.util.Locale.ROOT).contains("budget"));
    }

    private static SummaryCandidate candidate() {
        return new SummaryCandidate(
                new SummaryMessage("m", 4, "u", "An", "ignored"),
                List.of("the", "budget", "review"),
                List.of("the", "Budget", "review"),
                1.0);
    }
}
