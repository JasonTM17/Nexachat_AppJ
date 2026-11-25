package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Tokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local, deterministic extraction for high-confidence factoid answers. It never
 * sends text anywhere: exact values stay inside the server trust boundary and
 * only the minimal supporting sentence or value span becomes the answer.
 */
final class RagExtractiveAnswerer {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+\\b");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])(?:(?:https?|ftp)://|www\\.)[^\\s<>\\\"']+"
                    + "|(?<![@\\p{L}\\p{N}_])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}"
                    + "(?::\\d+)?(?:/[^\\s<>\\\"']*)?");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\+?\\p{Nd}[\\p{Nd}(). -]{4,}\\p{Nd}(?![\\p{L}\\p{N}])");
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\p{Nd}+(?:[.,:/-]\\p{Nd}+)*(?![\\p{L}\\p{N}])");

    private RagExtractiveAnswerer() {
    }

    static Result extract(
            String question,
            SummaryLanguage language,
            RagMessage message,
            int maxAnswerCodePoints) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return null;
        }
        String content = message.content();
        List<String> terms = RagQuestionTerms.extract(question, language, 12).stream()
                .filter(term -> !isRequestCue(term))
                .distinct()
                .toList();
        double coverage = termCoverage(content, terms);
        ExactMatch exact = findExactValue(question, content);
        if (exact != null) {
            String value = exact.value();
            return new Result(
                    truncateTail(value, maxAnswerCodePoints),
                    new AskExtraction("EXACT_VALUE", message.id(), exact.start(), exact.end(),
                            codePoints(value) > maxAnswerCodePoints, exact.type().name()),
                    true,
                    coverage);
        }

        List<Span> sentences = sentences(content);
        Span best = null;
        int bestMatches = 0;
        for (Span sentence : sentences) {
            String text = content.substring(sentence.start(), sentence.end());
            int matches = matchedTermCount(text, terms);
            if (matches > bestMatches) {
                best = sentence;
                bestMatches = matches;
            }
        }
        if (best == null || bestMatches == 0) {
            return null;
        }

        String sentence = content.substring(best.start(), best.end());
        boolean truncated = codePoints(sentence) > maxAnswerCodePoints;
        int answerStart = best.start();
        int answerEnd = best.end();
        String answer = sentence;
        if (truncated) {
            Window window = centeredWindow(sentence, terms, maxAnswerCodePoints);
            answer = sentence.substring(window.start(), window.end()).trim();
            answerStart = best.start() + window.start() + leadingWhitespace(answer, sentence.substring(window.start(), window.end()));
            answerEnd = answerStart + answer.length();
        }
        if (answer.isBlank()) {
            return null;
        }
        return new Result(
                answer,
                new AskExtraction("EVIDENCE_SPAN", message.id(), answerStart, answerEnd, truncated, null),
                false,
                coverage);
    }

    private static boolean isRequestCue(String term) {
        return Set.of(
                "find", "tell", "show", "what", "when", "where", "who", "which", "how",
                "tim", "cho", "biet", "cai", "gi", "khi", "nao", "dau", "ai").contains(term);
    }

    static boolean isFactoidRequest(String question) {
        String folded = Tokenizer.foldForMatching(question);
        if (folded.isBlank()) {
            return false;
        }
        return CueLike.interrogative(folded)
                || startsWithAny(folded, "find ", "tell me ", "show me ", "tim ", "cho toi biet ")
                || !requestedValueTypes(question).isEmpty();
    }

    private static ExactMatch findExactValue(String question, String content) {
        Set<ValueType> requested = requestedValueTypes(question);
        if (requested.isEmpty()) {
            return null;
        }
        List<ExactMatch> matches = new ArrayList<>();
        for (ValueType type : requested) {
            Pattern pattern = switch (type) {
                case EMAIL -> EMAIL;
                case PHONE -> PHONE;
                case URL -> URL;
                case NUMBER -> NUMBER;
            };
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                int end = matcher.end();
                while (end > matcher.start() && ".,;:!?)]} ".indexOf(content.charAt(end - 1)) >= 0) {
                    end--;
                }
                if (end <= matcher.start()) {
                    continue;
                }
                String value = content.substring(matcher.start(), end).trim();
                if (type == ValueType.PHONE) {
                    long digits = value.codePoints().filter(Character::isDigit).count();
                    if (digits < 7 || digits > 15) {
                        continue;
                    }
                }
                if (!value.isBlank()) {
                    matches.add(new ExactMatch(type, value, matcher.start(), end));
                }
            }
        }
        return matches.stream()
                .min(Comparator.comparingInt(ExactMatch::start)
                        .thenComparingInt(match -> match.type().ordinal()))
                .orElse(null);
    }

    private static Set<ValueType> requestedValueTypes(String question) {
        String folded = Tokenizer.foldForMatching(question);
        Set<ValueType> types = new LinkedHashSet<>();
        if (containsAnyWord(folded, "phone", "telephone", "mobile", "sdt")
                || folded.contains("dien thoai") || folded.contains("so dien thoai")) {
            types.add(ValueType.PHONE);
        }
        if (containsAnyWord(folded, "email", "e-mail", "mail")) {
            types.add(ValueType.EMAIL);
        }
        if (folded.contains("lien he") || containsAnyWord(folded, "contact")) {
            types.add(ValueType.PHONE);
            types.add(ValueType.EMAIL);
        }
        if (containsAnyWord(folded, "url", "link", "website", "domain")
                || folded.contains("web site") || folded.contains("duong dan")) {
            types.add(ValueType.URL);
        }
        if (containsAnyWord(folded, "number", "code", "version", "amount", "budget", "price", "cost", "so", "ma")) {
            types.add(ValueType.NUMBER);
        }
        return types;
    }

    private static boolean containsAnyWord(String text, String... words) {
        for (String word : words) {
            Pattern pattern = Pattern.compile(
                    "(?u)(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])");
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, String... prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static double termCoverage(String content, List<String> terms) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        return (double) matchedTermCount(content, terms) / terms.size();
    }

    private static int matchedTermCount(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return 0;
        }
        String folded = Tokenizer.foldForMatching(text);
        int matched = 0;
        for (String term : new LinkedHashSet<>(terms)) {
            if (!term.isBlank() && folded.contains(term)) {
                matched++;
            }
        }
        return matched;
    }

    private static List<Span> sentences(String content) {
        List<Span> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            boolean punctuationEnd = ".!?".indexOf(current) >= 0
                    && (index + 1 == content.length() || Character.isWhitespace(content.charAt(index + 1)));
            boolean newlineEnd = current == '\n' || current == '\r';
            if (!punctuationEnd && !newlineEnd) {
                continue;
            }
            addTrimmed(result, content, start, index + 1);
            start = index + 1;
        }
        addTrimmed(result, content, start, content.length());
        return List.copyOf(result);
    }

    private static void addTrimmed(List<Span> output, String content, int start, int end) {
        while (start < end && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            output.add(new Span(start, end));
        }
    }

    private static Window centeredWindow(String sentence, List<String> terms, int maxCodePoints) {
        int length = codePoints(sentence);
        if (length <= maxCodePoints) {
            return new Window(0, sentence.length());
        }
        String lower = sentence.toLowerCase(Locale.ROOT);
        int pivot = -1;
        for (String term : terms) {
            int position = lower.indexOf(term.toLowerCase(Locale.ROOT));
            if (position >= 0 && (pivot < 0 || position < pivot)) {
                pivot = position;
            }
        }
        int pivotCodePoint = pivot < 0 ? 0 : sentence.codePointCount(0, pivot);
        int startCodePoint = Math.max(0, pivotCodePoint - maxCodePoints / 3);
        startCodePoint = Math.min(startCodePoint, Math.max(0, length - maxCodePoints));
        int endCodePoint = Math.min(length, startCodePoint + maxCodePoints);
        int start = sentence.offsetByCodePoints(0, startCodePoint);
        int end = sentence.offsetByCodePoints(0, endCodePoint);
        return new Window(start, end);
    }

    private static int leadingWhitespace(String trimmed, String original) {
        int leading = 0;
        while (leading < original.length()
                && leading < trimmed.length() + original.length()
                && Character.isWhitespace(original.charAt(leading))) {
            leading++;
        }
        return leading;
    }

    private static String truncateTail(String value, int maxCodePoints) {
        if (codePoints(value) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private enum ValueType {
        PHONE,
        EMAIL,
        URL,
        NUMBER
    }

    private record ExactMatch(ValueType type, String value, int start, int end) {
    }

    private record Span(int start, int end) {
    }

    private record Window(int start, int end) {
    }

    /** Small local facade to keep interrogative detection independent of LLM extraction. */
    private static final class CueLike {
        private static boolean interrogative(String folded) {
            return folded.endsWith("?")
                    || startsWithAny(folded, "what ", "when ", "where ", "who ", "which ", "how ")
                    || startsWithAny(folded, "cai gi ", "khi nao ", "o dau ", "ai ", "nhu the nao ");
        }
    }

    record Result(String answer, AskExtraction extraction, boolean exactValue, double coverage) {
    }
}
