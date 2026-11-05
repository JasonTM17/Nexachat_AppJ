package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldSetDevelopmentEvaluationTest {
    @Test
    void reportsDevelopmentMetricsWithoutReadingHeldOutLabelsForTuning() {
        var development = SummaryGoldSet.split("DEV");
        assertEquals(12, development.size());
        SummaryGoldSet.print("DEV", development);
    }
}
