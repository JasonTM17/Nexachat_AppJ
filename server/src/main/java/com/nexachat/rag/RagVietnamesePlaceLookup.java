package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Tokenizer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A place question about what a named speaker says they will do. */
record RagVietnamesePlaceLookup(String action, String aspect) {
    private static final String ASPECT = "(?:(?<aspect>sẽ|se|đã|da|đang|dang) )?";
    private static final Pattern QUESTION = Pattern.compile(
            "\\A(?:anh|chị|chi|cô|co|chú|chu|bác|bac|ông|ong|bà|ba|em|bạn|ban) (?:ấy|ay) "
                    + ASPECT + "(?<action>[^,;?!]+?) (?:ở đâu|o dau|tại đâu|tai dau)"
                    + RagVietnameseDateLookup.QUESTION_END);
    private static final Pattern CLAUSES = Pattern.compile("[,;]| (?:còn|con|nhưng|nhung) ");
    private static final Pattern STATEMENT = Pattern.compile(
            "\\A(?:tôi|toi|mình|minh|tớ|to|chúng tôi|chung toi|chúng mình|chung minh) "
                    + ASPECT + "(?<action>.+?) (?:ở|o|tại|tai) (?<place>[^.!?]+?)"
                    + "(?: (?:lúc|luc|vào|vao|từ|tu|sau khi|trước khi|truoc khi) .+)?[.!]?\\z");
    private static final Pattern UNRESOLVED_PLACE = Pattern.compile(
            "\\A(?:(?:(?:chỗ|cho) )?(?:đây|day|đó|do|kia|đâu|dau|cũ|cu|ấy|ay)"
                    + "|(?:đâu|dau) (?:đó|do)|(?:(?:một|mot) )?(?:nơi|noi|chỗ|cho) (?:nào|nao) (?:đó|do))\\z"
                    + "|(?U)\\b(?:hoặc|hoac|hay|chưa|chua|sẽ báo|se bao)\\b");
    private static final Pattern PLACE_ALTERNATIVE = Pattern.compile("(?U)[,;]\\s*(?:hoặc|hoac|hay)\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    static RagVietnamesePlaceLookup parse(String question, SummaryLanguage language, boolean namedSpeaker) {
        if (!namedSpeaker || language != SummaryLanguage.VI) {
            return null;
        }
        Matcher matcher = QUESTION.matcher(normalize(question));
        return matcher.matches() ? new RagVietnamesePlaceLookup(matcher.group("action"), aspectOf(matcher)) : null;
    }

    Set<String> places(String statement) {
        String normalized = normalize(statement);
        if (PLACE_ALTERNATIVE.matcher(normalized).find()) {
            return Set.of();
        }
        var places = new LinkedHashSet<String>();
        for (String clause : CLAUSES.split(normalized)) {
            Matcher matcher = STATEMENT.matcher(clause.strip());
            if (matcher.matches() && action.equals(matcher.group("action"))
                    && (aspect.isEmpty() || aspect.equals(aspectOf(matcher)))) {
                String place = matcher.group("place");
                if (!UNRESOLVED_PLACE.matcher(place).find()) {
                    places.add(place);
                }
            }
        }
        return places;
    }

    private static String aspectOf(Matcher matcher) {
        String value = matcher.group("aspect");
        return value == null ? "" : Tokenizer.foldForMatching(value);
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(Tokenizer.nfc(text).strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
