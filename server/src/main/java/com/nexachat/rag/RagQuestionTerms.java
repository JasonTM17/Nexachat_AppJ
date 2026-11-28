package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.StopWords;
import com.nexachat.summary.engine.Tokenizer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Folded content terms used for lexical retrieval and MMR similarity. */
public final class RagQuestionTerms {

    private static final Set<String> EN_QUERY_SCAFFOLD_TERMS = Set.of(
            "about", "tell", "find", "explain", "need", "detail", "details", "remind",
            "give", "show", "please", "exact", "exactly", "how", "much", "many",
            "decide", "decided", "describe", "want", "know", "should");
    private static final Set<String> VI_QUERY_SCAFFOLD_TERMS = Set.of(
            "gi", "nao", "biet", "giup", "xin", "can", "muon");
    private static final List<String> VI_QUERY_SCAFFOLD_PHRASES = List.of(
            "giai thich", "tim kiem", "tim hieu", "tim cach", "bao nhieu", "chi tiet",
            "vui long", "the nao", "khi nao", "goi y");
    // Match before folding to preserve words such as "ở đầu", "máy" and the name "Nhi".
    static final String VI_PLACE_VALUE = "(?:đâu|dau|(?:chỗ|cho|phòng|phong|địa điểm|dia diem)\\s+(?:nào|nao))";
    private static final Pattern VI_PLACE_CUE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:ở|o|tại|tai)\\s+" + VI_PLACE_VALUE + "(?![\\p{L}\\p{N}])");
    private static final Pattern VI_CLOCK_CUE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:(?:lúc|luc)\\s+)?(?:mấy\\s+giờ|may\\s+gio)(?![\\p{L}\\p{N}])");
    private static final Pattern VI_QUESTION_PARTICLE = Pattern.compile("(?iu)\\s+nhỉ\\s*(?=[?!]*\\z)");

    private RagQuestionTerms() {
    }

    public static List<String> extract(String question, SummaryLanguage language, int limit) {
        return contentTerms(question, language).stream()
                .distinct()
                .limit(Math.max(1, limit))
                .toList();
    }

    static boolean hasVietnamesePlaceOrTimeCue(String question) {
        return hasVietnamesePlaceCue(question) || hasVietnameseClockCue(question);
    }

    static boolean hasVietnamesePlaceCue(String question) {
        return VI_PLACE_CUE.matcher(Tokenizer.nfc(question)).find();
    }

    static boolean hasVietnameseClockCue(String question) {
        return VI_CLOCK_CUE.matcher(Tokenizer.nfc(question)).find();
    }

    static List<String> answerTerms(String question, SummaryLanguage language, int limit) {
        if (language != SummaryLanguage.VI) {
            return extract(question, language, limit).stream()
                    .filter(term -> !EN_QUERY_SCAFFOLD_TERMS.contains(term))
                    .toList();
        }

        String withoutCues = VI_PLACE_CUE.matcher(Tokenizer.nfc(question)).replaceAll(" ");
        withoutCues = VI_CLOCK_CUE.matcher(withoutCues).replaceAll(" ");
        withoutCues = VI_QUESTION_PARTICLE.matcher(withoutCues).replaceFirst("");
        String normalized = " " + RagQuestionNormalizer.normalize(withoutCues) + " ";
        Set<String> scaffold = new HashSet<>(VI_QUERY_SCAFFOLD_TERMS);
        for (String phrase : VI_QUERY_SCAFFOLD_PHRASES) {
            if (normalized.contains(" " + phrase + " ")) {
                scaffold.addAll(List.of(phrase.split(" ")));
            }
        }
        List<String> terms = extract(withoutCues, language, limit);
        if (!terms.isEmpty() && terms.getFirst().equals("tim")) {
            scaffold.add("tim");
        }
        return terms.stream().filter(term -> !scaffold.contains(term)).toList();
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
