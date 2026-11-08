package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.StopWords;
import com.nexachat.summary.engine.Tokenizer;

import java.util.List;

/** Folded content terms used for lexical retrieval and MMR similarity. */
public final class RagQuestionTerms {

    private RagQuestionTerms() {
    }

    public static List<String> extract(String question, SummaryLanguage language, int limit) {
        return contentTerms(question, language).stream()
                .distinct()
                .limit(Math.max(1, limit))
                .toList();
    }

    public static List<String> contentTerms(String text, SummaryLanguage language) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        com.nexachat.summary.engine.SummaryLanguage engineLanguage =
                language == null
                        ? com.nexachat.summary.engine.SummaryLanguage.AUTO
                        : com.nexachat.summary.engine.SummaryLanguage.valueOf(language.name());
        return Tokenizer.tokenData(text).stream()
                .map(Tokenizer.Token::folded)
                .filter(token -> token.length() > 1)
                .filter(token -> !token.codePoints().allMatch(Character::isDigit))
                .filter(token -> !StopWords.contains(token, engineLanguage))
                .toList();
    }
}
