package com.nexachat.summary.engine;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageDetectorTest {

    @Test
    void vietnameseDiacriticsAndStopWordsSelectVietnamese() {
        SummaryWindow window = new SummaryWindow(java.util.List.of(
                new SummaryMessage("m1", 1, "u1", "An", "Nhóm sẽ chốt việc ngày mai")));

        assertEquals(SummaryLanguage.VI, LanguageDetector.detect(window));
    }

    @Test
    void englishTextSelectsEnglishAndNullMessagesAreSkipped() {
        SummaryWindow window = new SummaryWindow(Arrays.asList(
                null,
                new SummaryMessage("m1", 1, "u1", "An", "The team will review the budget")));

        assertEquals(SummaryLanguage.EN, LanguageDetector.detect(window));
    }

    @Test
    void emptyWindowFallsBackToEnglish() {
        assertEquals(SummaryLanguage.EN, LanguageDetector.detect(new SummaryWindow(java.util.List.of())));
    }
}
