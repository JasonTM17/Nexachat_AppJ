package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Tokenizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String IDENTIFIER_LABEL = "(?:codes?|pin|mã|ma so|serial number|tracking number)";
    private static final Pattern IDENTIFIER_REQUEST = Pattern.compile("\\b" + IDENTIFIER_LABEL + "\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}_-]+");
    private static final Pattern LABELLED_IDENTIFIER = Pattern.compile("\\b" + IDENTIFIER_LABEL + "\\b"
            + "(?:\\s+(?:[\\p{L}\\p{M}]+\\s+){0,8}?(?:is|was|là|la)\\s+|\\s*[:=]\\s*|\\s+)"
            + "([\\p{L}\\p{M}\\p{Nd}][\\p{L}\\p{M}\\p{Nd}_-]*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<String> EN_INFLECTION_SUFFIXES = List.of("s", "es", "d", "ed", "ing");
    private static final Pattern TENTATIVE_EVIDENCE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:maybe|perhaps|tentatively|might|could|let['’]s"
                    + "|có lẽ|hình như|chưa chắc|có thể|co le|hinh nhu|chua chac|co the)(?![\\p{L}\\p{N}])");
    private static final Pattern VALUE_NEGATION = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:not|isn['’]t|aren['’]t|wasn['’]t|no longer"
                    + "|không phải|không còn|khong phai|khong con)(?![\\p{L}\\p{N}])");
    private static final Pattern QUANTITY_QUESTION = Pattern.compile(
            "\\b(?:how many|bao nhieu)\\s+([\\p{L}]+)");
    private static final Pattern QUANTITY_WITH_UNIT = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,:/+\\-])(\\p{Nd}+)\\s+([\\p{L}\\p{M}]+)");
    private static final Pattern NON_EXACT_QUANTITY = Pattern.compile(
            "\\b(?:at least|less than|fewer than|(?<!no )more than|or more|or fewer|roughly|approximately|around|about"
                    + "|it nhat|toi thieu|nhieu hon|it hon|khoang|tam|tro len|tro xuong)\\b");
    private static final Pattern MAXIMUM_QUANTITY = Pattern.compile(
            "\\b(?:maximum|max|up to|at most|no more than|toi da|khong qua)\\b");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile(
            "(?iu)\\s*(?:;|,(?=\\s))\\s*|\\s+(?:while|whereas|but|còn|nhưng)\\s+");
    private static final Pattern CONDITIONAL_CUE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:if|unless|pending|proposed|nếu|neu|dự kiến|du kien)"
                    + "(?![\\p{L}\\p{N}])");
    private static final Pattern UNSET_ATTRIBUTED_FACT = Pattern.compile(
            "(?iu)\\b(?:undecided|unknown|not (?:yet )?(?:set|fixed|decided|confirmed|assigned|chosen|scheduled)"
                    + "|(?:have|has)n['’]t (?:yet )?(?:set|fixed|decided|confirmed|assigned|chosen|scheduled)"
                    + "|chưa (?:chốt|ấn định|xác nhận|biết|chọn)|không rõ"
                    + "|chua (?:chot|an dinh|xac nhan|biet|chon)|khong ro)\\b");
    private static final int VI_GRAMMAR_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern VI_DATE_REQUEST = Pattern.compile(
            "\\b(?:(?:ngày|ngay)\\s+(?:nào|nao|mấy|may)|(?:hôm|hom)\\s+(?:nào|nao))\\s*[?!]*\\z",
            VI_GRAMMAR_FLAGS);
    private static final Pattern VI_DATE_PENDING_CONFIRMATION = Pattern.compile(
            "\\b(?:chờ|đợi|cho|doi)\\s+(?:thông báo|thong bao|xác nhận|xac nhan|phê duyệt|phe duyet)"
                    + "\\s+(?:chính thức|chinh thuc|cuối cùng|cuoi cung)\\b"
                    + "(?!\\s++(?:về|ve|cho|của|cua)\\s++(?!(?:ngày|ngay|lịch|lich|hạn|han"
                    + "|mốc|moc|thời\\s+điểm|thoi\\s+diem)\\b))",
            VI_GRAMMAR_FLAGS);
    private static final Pattern VI_RELATIVE_EVENT = Pattern.compile(
            "\\b(?:trước|truoc|sau)\\s+(?:buổi|buoi|khi|lúc|luc|ngày|ngay|hôm|hom)\\b",
            VI_GRAMMAR_FLAGS);
    private static final Pattern VI_DIRECT_DATE = Pattern.compile(
            "(?:\\b(?:là|la|vào|vao)\\s+|:\\s*)(?:(?:ngày|ngay)\\s+)?"
                    + "(?:\\p{Nd}{1,2}(?:[/.-]\\p{Nd}{1,2}|\\s+(?:tháng|thang)\\s+[\\p{L}\\p{Nd}]+)"
                    + "|(?:thứ|thu)\\s+(?:hai|ba|tư|tu|năm|nam|sáu|sau|bảy|bay)|chủ\\s+nhật|chu\\s+nhat)\\b",
            VI_GRAMMAR_FLAGS);

    private RagExtractiveAnswerer() {
    }

    static boolean hasConflictingClaims(
            String question,
            SummaryLanguage language,
            List<RagMessage> candidates,
            AskDeadline deadline,
            boolean namedSpeaker) {
        RagVietnameseDateLookup dateLookup = RagVietnameseDateLookup.parse(question, language, namedSpeaker);
        if (dateLookup != null) {
            return hasConflictingDates(candidates, deadline, dateLookup);
        }
        String foldedQuestion = RagQuestionNormalizer.normalize(question);
        Matcher quantityQuestion = QUANTITY_QUESTION.matcher(foldedQuestion);
        String unit = quantityQuestion.find() ? quantityQuestion.group(1) : null;
        boolean asksForMaximum = MAXIMUM_QUANTITY.matcher(foldedQuestion).find();
        Set<ValueType> types = unit == null ? requestedValueTypes(question) : Set.of();
        boolean identifierLookup = isIdentifierLookup(question, types);
        if (unit == null && types.isEmpty()) {
            return false;
        }
        // Keep all distinguishing words, such as a requested edition or place, even if
        // retrieval had to limit its query terms. See each clause as its own assertion.
        List<String> terms = RagQuestionTerms.answerTerms(question, language, Integer.MAX_VALUE);
        if (terms.isEmpty()) {
            return false;
        }
        Map<ValueType, String> values = new EnumMap<>(ValueType.class);
        for (RagMessage candidate : candidates) {
            deadline.check();
            String content = candidate.content();
            for (Span sentence : sentences(content)) {
                String text = content.substring(sentence.start(), sentence.end());
                if (!isEvidenceSentence(text, true) || CONDITIONAL_CUE.matcher(text).find()) {
                    continue;
                }
                for (String clause : CLAUSE_BOUNDARY.split(text)) {
                    if (matchedTermCount(clause, terms, language) != terms.size()
                            || VALUE_NEGATION.matcher(clause).find()) {
                        continue;
                    }
                    ClaimValue value = claimValue(clause, types, unit, asksForMaximum, language, identifierLookup);
                    if (value == null) {
                        continue;
                    }
                    String previous = values.putIfAbsent(value.type(), value.value());
                    if (previous != null && !previous.equals(value.value())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasConflictingDates(
            List<RagMessage> candidates, AskDeadline deadline, RagVietnameseDateLookup lookup) {
        String confirmed = null;
        for (RagMessage candidate : candidates) {
            deadline.check();
            for (String statement : attributedStatements(candidate.content(), true)) {
                for (String date : lookup.inspect(statement).values()) {
                    if (confirmed != null && !confirmed.equals(date)) {
                        return true;
                    }
                    confirmed = date;
                }
            }
        }
        return false;
    }

    private static ClaimValue claimValue(
            String clause, Set<ValueType> types, String unit, boolean asksForMaximum, SummaryLanguage language,
            boolean identifierLookup) {
        if (unit == null) {
            ExactMatch exact = findClaimValue(clause, types, new Span(0, clause.length()), identifierLookup);
            return exact == null ? null : new ClaimValue(exact.type(), exact.value());
        }
        String folded = RagQuestionNormalizer.normalize(clause);
        if (NON_EXACT_QUANTITY.matcher(folded).find()
                || (!asksForMaximum && MAXIMUM_QUANTITY.matcher(folded).find())) {
            return null;
        }
        Matcher matcher = QUANTITY_WITH_UNIT.matcher(clause);
        String count = null;
        while (matcher.find()) {
            String noun = Tokenizer.foldForMatching(matcher.group(2));
            if (!noun.equals(unit) && !(language != SummaryLanguage.VI && matchesInflection(Set.of(noun), unit))) {
                continue;
            }
            String digits = matcher.group(1).codePoints()
                    .collect(StringBuilder::new, (builder, digit) -> builder.append(Character.digit(digit, 10)),
                            StringBuilder::append).toString().replaceFirst("^0+(?!$)", "");
            if (count != null && !count.equals(digits)) {
                // A range or a clause with several quantities does not assert one value.
                return null;
            }
            count = digits;
        }
        return count == null ? null : new ClaimValue(ValueType.NUMBER, count);
    }

    private record ClaimValue(ValueType type, String value) {
    }

    static Result extract(
            String question,
            SummaryLanguage language,
            List<String> terms,
            RagMessage message,
            int maxAnswerCodePoints) {
        return extract(question, language, terms, message, maxAnswerCodePoints, false);
    }

    static Result extract(String question, SummaryLanguage language, List<String> terms, RagMessage message,
                          int maxAnswerCodePoints, boolean namedSpeaker) {
        if (message == null || message.content().isBlank() || terms.isEmpty()) {
            return null;
        }
        String content = message.content();
        boolean needsSettledEvidence = needsSettledEvidence(question, language);
        RagVietnameseDateLookup dateLookup = RagVietnameseDateLookup.parse(question, language, namedSpeaker);
        boolean attributedDate = namedSpeaker && asksForVietnameseDate(question, language);
        Set<ValueType> requestedTypes = dateLookup == null ? requestedValueTypes(question) : Set.of();
        boolean identifierLookup = isIdentifierLookup(question, requestedTypes);
        Span best = null;
        ExactMatch exact = null;
        int bestMatches = 0;
        boolean conflictingValues = false;
        for (Span sentence : sentences(content)) {
            String text = content.substring(sentence.start(), sentence.end());
            if (namedSpeaker ? !isAttributedStatement(text, attributedDate)
                    : !isEvidenceSentence(text, needsSettledEvidence)) {
                continue;
            }
            RagVietnameseDateLookup.DateEvidence date = dateLookup == null ? null : dateLookup.inspect(text);
            if (date != null && !date.usable()) {
                continue;
            }
            int matches = date != null && date.directlyBound() ? terms.size() : matchedTermCount(text, terms, language);
            if (matches == 0 || matches < bestMatches) {
                continue;
            }
            // Mixed identifiers stay in the sentence. A digit suffix or a nearby duration
            // can otherwise masquerade as the requested whole code.
            ExactMatch value = findClaimValue(content, requestedTypes, sentence, identifierLookup);
            if (value != null && identifierLookup && value.value().codePoints().anyMatch(Character::isLetter)) {
                value = null;
            }
            if (matches > bestMatches) {
                best = sentence;
                bestMatches = matches;
                exact = value;
                conflictingValues = false;
            } else if (value != null) {
                if (exact == null) {
                    best = sentence;
                    exact = value;
                } else if (exact.type() != value.type() || !exact.value().equals(value.value())) {
                    conflictingValues = true;
                }
            }
        }
        if (best == null) {
            return null;
        }

        double coverage = (double) bestMatches / terms.size();
        if (conflictingValues) {
            return new Result(null, null, false, coverage, true);
        }
        if (exact != null) {
            String value = exact.value();
            return new Result(
                    truncateTail(value, maxAnswerCodePoints),
                    new AskExtraction("EXACT_VALUE", message.id(), exact.start(), exact.end(),
                            codePoints(value) > maxAnswerCodePoints, exact.type().name()),
                    true,
                    coverage,
                    false);
        }

        String sentence = content.substring(best.start(), best.end());
        boolean truncated = codePoints(sentence) > maxAnswerCodePoints;
        if (namedSpeaker && truncated) {
            return null;
        }
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
                coverage,
                false);
    }

    static boolean needsSettledEvidence(String question, SummaryLanguage language) {
        return RagQuestionTerms.hasVietnamesePlaceOrTimeCue(question)
                || language != SummaryLanguage.VI
                && containsAnyWord(Tokenizer.foldForMatching(question), "should", "where", "when");
    }

    static List<String> settledStatements(String content) {
        return sentences(content).stream()
                .map(span -> content.substring(span.start(), span.end()))
                .filter(text -> isEvidenceSentence(text, true))
                .toList();
    }

    static List<String> attributedStatements(String content) {
        return attributedStatements(content, false);
    }

    static List<String> attributedStatements(String content, boolean attributedDate) {
        return attributedStatements(content, attributedDate, null);
    }

    static List<String> attributedStatements(String content, boolean attributedDate, RagVietnameseDateLookup lookup) {
        return sentences(content).stream().map(span -> content.substring(span.start(), span.end()))
                .filter(text -> isAttributedStatement(text, attributedDate))
                .filter(text -> lookup == null || lookup.inspect(text).usable())
                .toList();
    }

    static boolean asksForVietnameseDate(String question, SummaryLanguage language) {
        return language == SummaryLanguage.VI && VI_DATE_REQUEST.matcher(Tokenizer.nfc(question)).find();
    }

    private static boolean isAttributedStatement(String text, boolean attributedDate) {
        return isEvidenceSentence(text, true)
                && !CONDITIONAL_CUE.matcher(text).find()
                && !UNSET_ATTRIBUTED_FACT.matcher(text).find()
                && (!attributedDate || !VI_DATE_PENDING_CONFIRMATION.matcher(Tokenizer.nfc(text)).find()
                        && !onlyDatesARelativeEvent(text));
    }

    private static boolean onlyDatesARelativeEvent(String text) {
        Matcher relativeEvent = VI_RELATIVE_EVENT.matcher(text);
        // A date in the reference event cannot date the requested action. An explicit date
        // before the relative phrase remains usable, with the qualification in the quote.
        return relativeEvent.find() && !VI_DIRECT_DATE.matcher(text).region(0, relativeEvent.start()).find();
    }

    private static boolean isEvidenceSentence(String text, boolean needsSettledEvidence) {
        return !text.endsWith("?")
                && (!needsSettledEvidence || !TENTATIVE_EVIDENCE.matcher(Tokenizer.nfc(text)).find());
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

    private static ExactMatch findExactValue(Set<ValueType> requested, String content, Span sentence) {
        if (requested.isEmpty() || VALUE_NEGATION.matcher(content).region(sentence.start(), sentence.end()).find()) {
            return null;
        }
        ExactMatch match = null;
        for (ValueType type : requested) {
            Pattern pattern = switch (type) {
                case EMAIL -> EMAIL;
                case PHONE -> PHONE;
                case URL -> URL;
                case NUMBER -> NUMBER;
            };
            Matcher matcher = pattern.matcher(content).region(sentence.start(), sentence.end());
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
                if (match != null && !match.value().equals(value)) {
                    // A single scalar would discard the distinction between values in this sentence.
                    return null;
                }
                if (match == null) {
                    match = new ExactMatch(type, value, matcher.start(), end);
                }
            }
        }
        return match;
    }

    private static boolean isIdentifierLookup(String question, Set<ValueType> types) {
        return types.contains(ValueType.NUMBER) && IDENTIFIER_REQUEST.matcher(Tokenizer.nfc(question)).find();
    }

    private static ExactMatch findClaimValue(String content, Set<ValueType> types, Span sentence, boolean identifierLookup) {
        if (identifierLookup && IDENTIFIER_TOKEN.matcher(content).region(sentence.start(), sentence.end()).results()
                .map(match -> match.group()).anyMatch(value -> value.codePoints().anyMatch(Character::isLetter)
                        && value.codePoints().anyMatch(Character::isDigit))) {
            return findLabelledIdentifier(content, sentence);
        }
        return findExactValue(types, content, sentence);
    }

    private static ExactMatch findLabelledIdentifier(String content, Span sentence) {
        if (VALUE_NEGATION.matcher(content).region(sentence.start(), sentence.end()).find()) {
            return null;
        }
        Matcher matcher = LABELLED_IDENTIFIER.matcher(content).region(sentence.start(), sentence.end());
        ExactMatch found = null;
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!value.codePoints().anyMatch(Character::isDigit)) {
                continue;
            }
            if (found != null && !found.value().equals(value)) {
                return null;
            }
            found = new ExactMatch(ValueType.NUMBER, value, matcher.start(1), matcher.end(1));
        }
        return found;
    }

    private static Set<ValueType> requestedValueTypes(String question) {
        String folded = Tokenizer.foldForMatching(question);
        if (RagQuestionTerms.hasVietnamesePlaceOrTimeCue(question)
                || containsAnyWord(folded, "where", "when", "who", "why")) {
            return Set.of();
        }
        Set<ValueType> types = new LinkedHashSet<>();
        if (containsAnyWord(folded, "phone", "telephone", "mobile", "sdt")
                || folded.contains("dien thoai") || folded.contains("so dien thoai")) {
            types.add(ValueType.PHONE);
        }
        if (containsAnyWord(folded, "email", "e-mail", "mail")) {
            types.add(ValueType.EMAIL);
        }
        if (containsAnyWord(folded, "url", "link", "website", "domain")
                || folded.contains("web site") || folded.contains("duong dan")) {
            types.add(ValueType.URL);
        }
        if (types.isEmpty() && (folded.contains("lien he") || containsAnyWord(folded, "contact"))) {
            types.add(ValueType.PHONE);
            types.add(ValueType.EMAIL);
        }
        String original = Tokenizer.nfc(question).toLowerCase(Locale.ROOT);
        if (types.isEmpty() && (containsAnyWord(folded, "number", "code", "version", "amount", "budget", "price", "cost")
                || containsAnyWord(original, "số", "mã", "ma so", "so luong"))) {
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

    private static int matchedTermCount(String text, List<String> terms, SummaryLanguage language) {
        Set<String> tokens = new LinkedHashSet<>(RagQuestionTerms.contentTerms(text, language));
        int matched = 0;
        for (String term : terms) {
            if (tokens.contains(term) || language != SummaryLanguage.VI && matchesInflection(tokens, term)) {
                matched++;
            }
        }
        return matched;
    }

    private static boolean matchesInflection(Set<String> tokens, String term) {
        if (term.length() < 4) {
            return false;
        }
        for (String suffix : EN_INFLECTION_SUFFIXES) {
            if (tokens.contains(term + suffix)
                    || term.length() >= 4 + suffix.length() && term.endsWith(suffix)
                    && tokens.contains(term.substring(0, term.length() - suffix.length()))) {
                return true;
            }
        }
        return false;
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

    record Result(String answer, AskExtraction extraction, boolean exactValue, double coverage,
                  boolean conflictingValues) {
    }
}
