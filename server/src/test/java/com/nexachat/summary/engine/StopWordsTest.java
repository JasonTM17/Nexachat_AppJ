package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopWordsTest {

    @Test
    void vietnameseParticlesAreStopWordsInBothModes() {
        assertTrue(StopWords.contains("khong", SummaryLanguage.VI));
        assertTrue(StopWords.contains("nhe", SummaryLanguage.AUTO));
        assertTrue(StopWords.vietnamese().contains("cua"));
    }

    @Test
    void englishFunctionWordsAreStopWordsAndUnknownTokensAreKept() {
        assertTrue(StopWords.contains("would", SummaryLanguage.EN));
        assertTrue(StopWords.english().contains("the"));
        assertFalse(StopWords.contains("budget", SummaryLanguage.EN));
        assertFalse(StopWords.contains("chot", SummaryLanguage.VI));
        assertFalse(StopWords.contains(null, SummaryLanguage.VI));
    }
}
