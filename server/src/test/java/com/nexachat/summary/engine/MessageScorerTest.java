package com.nexachat.summary.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageScorerTest {

    @Test
    void emptyInputProducesNoCandidates() {
        assertTrue(MessageScorer.score(List.of(), SummaryLanguage.EN).isEmpty());
    }

    @Test
    void pinnedDuplicateOutranksTheSameWording() {
        SummaryMessage plain = new SummaryMessage("a", 1, "u", "An", "ok");
        SummaryMessage pinned = new SummaryMessage(
                "b",
                2,
                "u",
                "An",
                "ok",
                null,
                null,
                new SummarySignals(null, 0, true));

        List<SummaryCandidate> scored = MessageScorer.score(List.of(plain, pinned), SummaryLanguage.EN);

        assertEquals(2, scored.size());
        assertEquals(0.0, scored.get(0).score(), 1e-9);
        assertEquals(1.0, scored.get(1).score(), 1e-9);
    }
}
