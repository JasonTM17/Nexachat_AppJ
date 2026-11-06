package com.nexachat.summary.llm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryMessage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageCheckTest {

    @Test
    void detectsVietnameseAndEnglishFromHintWords() {
        assertEquals(SummaryLanguage.VI, LanguageCheck.detect(List.of(message("Nhóm sẽ chốt việc ngày mai"))));
        assertEquals(SummaryLanguage.EN, LanguageCheck.detect(List.of(message("The team will review the budget"))));
        assertEquals(SummaryLanguage.EN, LanguageCheck.detect(null));
    }

    @Test
    void matchRequiresLettersAndTheRequestedLanguage() {
        assertFalse(LanguageCheck.matches("   ", SummaryLanguage.VI));
        assertFalse(LanguageCheck.matches(null, SummaryLanguage.EN));
        assertTrue(LanguageCheck.matches("Nhóm sẽ chốt việc ngày mai", SummaryLanguage.VI));
        assertFalse(LanguageCheck.matches("The team will review the budget", SummaryLanguage.VI));
        assertTrue(LanguageCheck.matches("The team will review the budget", SummaryLanguage.EN));
    }

    private static SummaryMessage message(String text) {
        return new SummaryMessage(UUID.randomUUID(), 1, UUID.randomUUID(), "An", text, Instant.parse("2026-11-06T00:00:00Z"), null);
    }
}
