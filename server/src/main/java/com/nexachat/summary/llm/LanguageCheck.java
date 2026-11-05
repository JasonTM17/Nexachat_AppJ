package com.nexachat.summary.llm;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryMessage;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LanguageCheck {

    private static final Set<String> VIETNAMESE_HINTS = Set.of(
            "va", "la", "cua", "trong", "da", "se", "khong", "cho", "voi", "cac", "nhung", "mot",
            "ban", "toi", "nhom", "thi", "de", "can", "lam", "co", "ngay", "mai", "viec", "chua",
            "theo", "ve", "duoc", "phai", "nha", "minh", "anh", "chi", "em", "hom", "nay", "bao");
    private static final Set<String> ENGLISH_HINTS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "is", "are", "was", "were", "with",
            "for", "on", "by", "we", "you", "they", "will", "can", "should", "need", "our", "that",
            "this", "it", "from", "about", "has", "have", "not", "as", "at", "be", "their");

    private LanguageCheck() {
    }

    static SummaryLanguage detect(List<SummaryMessage> messages) {
        Signals signals = new Signals();
        if (messages != null) {
            messages.stream().filter(message -> message != null).map(SummaryMessage::text)
                    .filter(text -> text != null).forEach(signals::add);
        }
        return signals.detected();
    }

    static boolean matches(String text, SummaryLanguage language) {
        Signals signals = new Signals();
        signals.add(text == null ? "" : text);
        if (signals.letters == 0) {
            return false;
        }
        if (language == SummaryLanguage.VI) {
            return signals.vietnameseScore > 0 && signals.vietnameseScore >= signals.englishScore;
        }
        return language == SummaryLanguage.EN
                && signals.englishScore >= signals.vietnameseScore
                && (signals.englishScore > 0 || signals.vietnameseMarks == 0);
    }

    private static final class Signals {
        private int vietnameseScore;
        private int englishScore;
        private int vietnameseMarks;
        private int letters;

        private void add(String text) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
            int addedMarks = (int) normalized.codePoints()
                    .filter(codePoint -> Character.getType(codePoint) == Character.NON_SPACING_MARK)
                    .count();
            addedMarks += (int) text.codePoints().filter(codePoint -> codePoint == 'đ' || codePoint == 'Đ').count();
            vietnameseMarks += addedMarks;
            letters += (int) text.codePoints().filter(Character::isLetter).count();
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                if (token.isBlank()) {
                    continue;
                }
                String folded = Normalizer.normalize(token, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "").replace('đ', 'd');
                if (VIETNAMESE_HINTS.contains(folded)) {
                    vietnameseScore += 2;
                }
                if (ENGLISH_HINTS.contains(token)) {
                    englishScore += 2;
                }
            }
            vietnameseScore += addedMarks * 2;
        }

        private SummaryLanguage detected() {
            if (vietnameseScore == englishScore) {
                return vietnameseMarks > 0 ? SummaryLanguage.VI : SummaryLanguage.EN;
            }
            return vietnameseScore > englishScore ? SummaryLanguage.VI : SummaryLanguage.EN;
        }
    }
}
