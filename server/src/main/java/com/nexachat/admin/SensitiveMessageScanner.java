package com.nexachat.admin;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule engine for the sensitive-message pipeline.
 *
 * <p>Pattern types:
 * <ul>
 *   <li>{@code KEYWORD} — normalized substring match against the folded view
 *       of the text plus its leetspeak view, so {@code s3nd} still hits
 *       {@code send};</li>
 *   <li>{@code REGEX} — a bounded Java regex checked at write time by
 *       {@link #validatePattern} (length, no backrefs, no nested quantifiers)
 *       and applied to a {@value #MAX_SCAN_CHARS}-capped input;</li>
 *   <li>{@code CARD} — digit-run extraction with a Luhn check so only real
 *       payment-card numbers match, never arbitrary long numbers.</li>
 * </ul>
 *
 * <p>Nothing here ever persists or logs message content — the only artifact
 * that leaves the class is the redacted {@link #snippet} window.
 */
@Component
public class SensitiveMessageScanner {

    /** Input cap that bounds regex backtracking and keeps scans linear-ish. */
    static final int MAX_SCAN_CHARS = 4_000;

    /** Longest pattern an admin-authored rule may carry. */
    static final int MAX_PATTERN_CHARS = 200;

    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9][0-9\\s\\-]{6,}[0-9]");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_LIKE = Pattern.compile("\\+?[0-9][0-9\\s\\-]{7,}[0-9]");
    private static final Pattern OTP_LIKE = Pattern.compile("\\b[0-9]{4,8}\\b");
    private static final Pattern TOKEN_LIKE = Pattern.compile("\\b[A-Za-z0-9_\\-]{20,}\\b");

    /** Regex shapes rejected at write time: backrefs and nested quantifiers (ReDoS). */
    private static final Pattern BACKREFERENCE = Pattern.compile("\\\\[1-9]");
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile(
            "\\([^()]*[+*][^()]*\\)\\s*([+*]|\\{\\d)");
    private static final Pattern QUANTIFIED_DOT_STAR = Pattern.compile(
            "\\.\\*\\s*\\.\\*|\\.\\+\\s*\\.\\+|\\.\\*\\s*\\.\\+|\\.\\+\\s*\\.\\*");

    /** One rule hit, localized to the original text for the snippet window. */
    public record Match(AlertRuleRow rule, int offset, int length) {
    }

    /** Compiled view of a rule: regexes are precompiled once per scan call. */
    private record CompiledRule(AlertRuleRow row, Pattern regex) {
    }

    /**
     * Scans plaintext content against the given rules. Only the first
     * {@value #MAX_SCAN_CHARS} characters are considered — the same cap the
     * message-send path enforces is far above real message lengths, and the
     * cap bounds worst-case regex work.
     */
    public List<Match> scan(String content, List<AlertRuleRow> rules) {
        if (content == null || content.isEmpty() || rules.isEmpty()) {
            return List.of();
        }
        String capped = content.length() > MAX_SCAN_CHARS
                ? content.substring(0, MAX_SCAN_CHARS) : content;
        TextNormalizer.FoldedView foldedView = TextNormalizer.foldView(capped);
        String folded = foldedView.text();
        String leetView = TextNormalizer.leet(folded);
        List<Match> matches = new ArrayList<>();
        for (AlertRuleRow rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            switch (rule.patternType()) {
                case "KEYWORD" -> {
                    String needle = TextNormalizer.fold(rule.pattern());
                    if (needle.isEmpty()) {
                        continue;
                    }
                    int at = folded.indexOf(needle);
                    int len = needle.length();
                    if (at < 0) {
                        // The leet view is index-aligned with the folded one,
                        // so the same mapping translates it back.
                        at = leetView.indexOf(needle);
                    }
                    if (at >= 0) {
                        // A folded offset is NOT an original-text offset: the
                        // fold collapses whitespace runs and trims the edges,
                        // so slicing the original by a folded index brackets
                        // the wrong evidence. Map the range back instead.
                        matches.add(new Match(rule, foldedView.originalOffset(at),
                                foldedView.originalLength(at, len)));
                    }
                }
                case "REGEX" -> {
                    Pattern regex = compileQuietly(rule.pattern());
                    if (regex == null) {
                        continue;
                    }
                    Matcher matcher = regex.matcher(capped);
                    if (matcher.find()) {
                        matches.add(new Match(rule, matcher.start(),
                                Math.max(1, matcher.end() - matcher.start())));
                    }
                }
                case "CARD" -> {
                    Matcher matcher = DIGIT_RUN.matcher(capped);
                    while (matcher.find()) {
                        String digits = matcher.group().replaceAll("[^0-9]", "");
                        if (digits.length() >= 12 && digits.length() <= 19 && luhn(digits)) {
                            matches.add(new Match(rule, matcher.start(),
                                    matcher.end() - matcher.start()));
                            break;
                        }
                    }
                }
                default -> {
                    // Unknown pattern types never match — fail closed.
                }
            }
        }
        return matches;
    }

    /**
     * Redacted context window for an alert row: a bounded slice around the
     * first match with every sensitive-looking span (emails, phone-like
     * numbers, OTP-like digit groups, long token-like strings and Luhn-valid
     * card numbers) masked. Deterministic and content-derived only — never a
     * full-content copy.
     */
    public String snippet(String content, Match match, int radius) {
        if (content == null) {
            return "";
        }
        int safeRadius = Math.max(20, radius);
        int start = Math.max(0, match.offset() - safeRadius / 3);
        int end = Math.min(content.length(), match.offset() + match.length() + safeRadius);
        StringBuilder window = new StringBuilder();
        if (start > 0) {
            window.append('…');
        }
        window.append(content, start, end);
        if (end < content.length()) {
            window.append('…');
        }
        return redact(window.toString());
    }

    /** Deterministic redaction pass applied to snippets and stored context. */
    public String redact(String text) {
        if (text == null) {
            return "";
        }
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = redactLuhnRuns(out);
        out = PHONE_LIKE.matcher(out).replaceAll("[number]");
        out = OTP_LIKE.matcher(out).replaceAll("[code]");
        out = TOKEN_LIKE.matcher(out).replaceAll("[token]");
        return out;
    }

    /**
     * Write-time validation for admin-authored rule patterns. KEYWORD rules
     * just need a non-blank short pattern; REGEX rules must compile, stay
     * short and contain no backreferences or nested quantifiers (the classic
     * exponential-backtracking shape); CARD rules take no pattern.
     */
    public void validatePattern(String patternType, String pattern) {
        switch (patternType == null ? "" : patternType) {
            case "KEYWORD" -> {
                if (pattern == null || pattern.isBlank()
                        || pattern.length() > MAX_PATTERN_CHARS) {
                    throw invalidPattern("Keyword must be 1-" + MAX_PATTERN_CHARS + " characters");
                }
            }
            case "REGEX" -> {
                if (pattern == null || pattern.isBlank()
                        || pattern.length() > MAX_PATTERN_CHARS) {
                    throw invalidPattern("Regex must be 1-" + MAX_PATTERN_CHARS + " characters");
                }
                if (BACKREFERENCE.matcher(pattern).find()
                        || NESTED_QUANTIFIER.matcher(pattern).find()
                        || QUANTIFIED_DOT_STAR.matcher(pattern).find()) {
                    throw invalidPattern("Regex shape is not allowed (ReDoS risk)");
                }
                try {
                    Pattern.compile(pattern);
                } catch (java.util.regex.PatternSyntaxException e) {
                    throw invalidPattern("Regex does not compile: " + e.getDescription());
                }
            }
            case "CARD" -> {
                // No pattern to validate — the detector is fixed.
            }
            default -> throw invalidPattern("Unknown pattern type");
        }
    }

    private com.nexachat.common.error.ApiException invalidPattern(String message) {
        return com.nexachat.common.error.ApiException.of(
                com.nexachat.common.error.ApiErrorCode.VALIDATION_FAILED, message);
    }

    /** Luhn-valid card/ID-like runs are replaced with {@code [card]}. */
    private String redactLuhnRuns(String text) {
        Matcher matcher = DIGIT_RUN.matcher(text);
        StringBuffer out = new StringBuffer(text.length());
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[^0-9]", "");
            if (digits.length() >= 12 && digits.length() <= 19 && luhn(digits)) {
                matcher.appendReplacement(out, "[card]");
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Standard Luhn checksum for card/ID-shaped digit strings. */
    static boolean luhn(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private static Pattern compileQuietly(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            return null;
        }
    }

    /** Groups matches by category, picking the worst severity and all rule ids. */
    public Map<String, CategoryMatch> byCategory(List<Match> matches) {
        Map<String, CategoryMatch> grouped = new LinkedHashMap<>();
        for (Match match : matches) {
            CategoryMatch existing = grouped.get(match.rule().category());
            AlertSeverity severity = AlertSeverity.parse(match.rule().severity());
            if (existing == null) {
                grouped.put(match.rule().category(),
                        new CategoryMatch(match.rule().category(), severity,
                                new ArrayList<>(List.of(match.rule().id())), match));
            } else {
                existing.severity = AlertSeverity.max(existing.severity, severity);
                existing.ruleIds.add(match.rule().id());
            }
        }
        return grouped;
    }

    /** Mutable grouping bucket for {@link #byCategory}. */
    public static final class CategoryMatch {
        private final String category;
        private AlertSeverity severity;
        private final List<UUID> ruleIds;
        private final Match firstMatch;

        private CategoryMatch(String category, AlertSeverity severity, List<UUID> ruleIds,
                              Match firstMatch) {
            this.category = category;
            this.severity = severity;
            this.ruleIds = ruleIds;
            this.firstMatch = firstMatch;
        }

        public String category() {
            return category;
        }

        public AlertSeverity severity() {
            return severity;
        }

        public List<UUID> ruleIds() {
            return ruleIds;
        }

        public Match firstMatch() {
            return firstMatch;
        }
    }
}
