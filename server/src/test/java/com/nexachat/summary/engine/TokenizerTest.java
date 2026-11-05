package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerTest {
    @Test
    void normalizesUnicodeForMatchingAndFoldsVietnameseDiacritics() {
        String decomposed = "Nguye\u0302\u0303n đa\u0301 chô\u0301t";
        assertEquals("nguyen", Tokenizer.tokens(decomposed).get(0));
        assertTrue(Tokenizer.tokens(decomposed).contains("da"));
        assertTrue(Tokenizer.tokens(decomposed).contains("chot"));
    }

    @Test
    void preservesTheSourceMessageTextForExtractiveOutput() {
        String text = "Nhóm thống nhất dùng bảng màu xanh.";
        LocalSummary summary = new LocalExtractiveSummaryProvider().summarize(
                new SummaryWindow(java.util.List.of(new SummaryMessage("m1", 1, "u1", "A", text))),
                SummaryLanguage.VI);
        assertTrue(summary.decisions().stream().anyMatch(item -> item.text().equals(text)));
    }
}
