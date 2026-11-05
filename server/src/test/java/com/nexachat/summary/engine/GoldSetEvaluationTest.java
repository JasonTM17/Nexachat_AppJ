package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldSetEvaluationTest {
    private static final double MIN_DECISION_RECALL = 0.80;
    private static final double MIN_ACTION_RECALL = 0.75;
    private static final double MIN_QUESTION_RECALL = 0.80;

    @Test
    void heldOutGoldenConversationsMeetRecallAndCitationThresholds() {
        var all = SummaryGoldSet.load();
        var heldOut = all.stream().filter(conversation -> conversation.split().equals("HOLDOUT")).toList();
        var development = all.stream().filter(conversation -> conversation.split().equals("DEV")).toList();

        assertEquals(16, all.size());
        assertEquals(12, development.size());
        assertEquals(4, heldOut.size());
        assertEquals(6, all.stream().filter(conversation -> conversation.id().matches("vi\\d{2}")).count());
        assertEquals(5, all.stream().filter(conversation -> conversation.id().matches("en\\d{2}")).count());
        assertEquals(3, all.stream().filter(conversation -> conversation.id().matches("mix\\d{2}")).count());
        assertEquals(2, all.stream().filter(conversation -> conversation.id().equals("vi-noisy")
                || conversation.id().equals("en-long")).count());

        var evaluation = SummaryGoldSet.evaluate(heldOut);
        SummaryGoldSet.print("HOLDOUT", heldOut);
        assertTrue(evaluation.overall().decisions().recall() >= MIN_DECISION_RECALL,
                "Held-out decision recall is below the frozen threshold");
        assertTrue(evaluation.overall().actions().recall() >= MIN_ACTION_RECALL,
                "Held-out action recall is below the frozen threshold");
        assertTrue(evaluation.overall().questions().recall() >= MIN_QUESTION_RECALL,
                "Held-out question recall is below the frozen threshold");
        assertEquals(1.0, evaluation.overall().citationValidity(), 0.0,
                "Every emitted citation must refer to a message in the input window");
    }
}
