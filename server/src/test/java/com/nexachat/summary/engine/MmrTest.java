package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmrTest {
    @Test
    void skipsNearDuplicatesAndKeepsStableOrderingForEqualScores() {
        SummaryCandidate primary = candidate("a", 1, List.of("a", "b", "c", "d"), 1.0);
        SummaryCandidate duplicate = candidate("b", 2, List.of("a", "b", "c", "d", "e"), 0.99);
        SummaryCandidate diverse = candidate("c", 3, List.of("x", "y"), 0.65);

        List<SummaryCandidate> selected = Mmr.select(List.of(duplicate, diverse, primary), 2);
        assertEquals(List.of("a", "c"), selected.stream().map(value -> value.message().id()).toList());

        List<SummaryCandidate> equal = List.of(candidate("z", 7, List.of("same", "content"), 0.8),
                candidate("a", 7, List.of("same", "content"), 0.8));
        assertEquals("a", Mmr.select(equal, 1).get(0).message().id());
        assertTrue(Mmr.select(equal, 5).size() <= 2);
    }

    private static SummaryCandidate candidate(String id, long seq, List<String> terms, double score) {
        SummaryMessage message = new SummaryMessage(id, seq, "u1", "A", String.join(" ", terms));
        return new SummaryCandidate(message, terms, terms, score);
    }
}
