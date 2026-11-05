package com.nexachat.summary.engine;

import java.text.Normalizer;
import java.util.List;

public final class LanguageDetector {
    private LanguageDetector() {
    }

    public static SummaryLanguage detect(SummaryWindow window) {
        int vietnameseScore = 0;
        int englishScore = 0;
        int vietnameseMarks = 0;

        for (SummaryMessage message : window.messages()) {
            if (message == null || message.text() == null) {
                continue;
            }
            String text = Tokenizer.nfc(message.text());
            String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
            vietnameseMarks += (int) decomposed.codePoints()
                    .filter(codePoint -> Character.getType(codePoint) == Character.NON_SPACING_MARK)
                    .count();
            vietnameseMarks += (int) text.codePoints()
                    .filter(codePoint -> codePoint == 'đ' || codePoint == 'Đ')
                    .count();

            List<String> tokens = Tokenizer.tokens(text);
            for (String token : tokens) {
                if (StopWords.vietnamese().contains(token)) {
                    vietnameseScore += 2;
                }
                if (StopWords.english().contains(token)) {
                    englishScore += 2;
                }
            }
        }

        vietnameseScore += vietnameseMarks * 2;
        if (vietnameseScore == 0 && englishScore == 0) {
            return vietnameseMarks > 0 ? SummaryLanguage.VI : SummaryLanguage.EN;
        }
        if (vietnameseScore == englishScore) {
            return vietnameseMarks > 0 ? SummaryLanguage.VI : SummaryLanguage.EN;
        }
        return vietnameseScore > englishScore ? SummaryLanguage.VI : SummaryLanguage.EN;
    }
}
