package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Tokenizer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comparable values for clock times and counts of a requested Vietnamese unit. */
record RagVietnameseMeasureLookup(String unit, boolean maximum) {
    private static final List<String> DIGITS = List.of("khong", "mot", "hai", "ba", "tu", "nam", "sau", "bay", "tam", "chin");
    private static final String NONZERO_WORD = "(?:mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)";
    private static final String TENS = "(?:(?:(?:hai|ba|bon|tu|nam|sau|bay|tam|chin) )?muoi(?: " + NONZERO_WORD + ")?)";
    private static final String BELOW_HUNDRED = "(?:" + TENS + "|" + NONZERO_WORD + ")";
    private static final String SPOKEN_INTEGER = "(?:" + NONZERO_WORD + " tram(?: (?:(?:le|linh) )?"
            + BELOW_HUNDRED + ")?|" + BELOW_HUNDRED + ")";
    private static final String CLOCK_NUMBER = "(?:[0-9]{1,2}|" + BELOW_HUNDRED + ")";
    private static final Pattern QUANTITY_QUESTION = Pattern.compile("\\bbao nhieu ([\\p{L}]+)");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\p{L}\\p{N}.,:/+\\-–—])(?<count>[0-9]+|" + SPOKEN_INTEGER + ") (?<unit>[\\p{L}]+)(?![\\p{L}\\p{N}])");
    private static final Pattern NON_EXACT = Pattern.compile(
            "\\b(?:it nhat|toi thieu|nhieu hon|it hon|khoang|tro len|tro xuong|uoc chung)\\b"
                    + "|\\btam (?=[0-9])"
                    + "|\\b(?:[0-9]+|" + SPOKEN_INTEGER + ") (?:den|toi|hoac) (?=[0-9]|" + SPOKEN_INTEGER + "\\b)");
    private static final Pattern APPROXIMATE_WORD = Pattern.compile("(?iu)\\btầm\\b");
    private static final Pattern MAXIMUM = Pattern.compile("\\b(?:toi da|khong qua)\\b");
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![\\p{L}\\p{N}:/+\\-])(?:(?<marker>luc|vao) )?"
                    + "(?:(?<numericHour>[01]?[0-9]|2[0-3])(?::(?<colonMinute>[0-5][0-9])|h(?<hMinute>[0-5]?[0-9])?)"
                    + "|(?<spokenHour>" + CLOCK_NUMBER + ") gio(?: (?<spokenMinute>" + CLOCK_NUMBER + ")(?: phut)?)?(?! [0-9]))"
                    + "(?: (?<period>sang|trua|chieu|toi|dem))?(?![\\p{L}\\p{N}:/-])");
    private static final Pattern RELATIVE_PREFIX = Pattern.compile(
            "\\b(?:sau|truoc|trong|khoang|tam|som nhat|muon nhat) *\\z");
    private static final Pattern RELATIVE_SUFFIX = Pattern.compile("\\A *(?:sau|truoc|nua)\\b");

    static RagVietnameseMeasureLookup parse(String question, SummaryLanguage language) {
        if (language != SummaryLanguage.VI || RagVietnameseDateLookup.isDateQuestion(question)) {
            return null;
        }
        if (RagQuestionTerms.hasVietnameseClockCue(question)) {
            return new RagVietnameseMeasureLookup(null, false);
        }
        String folded = RagQuestionNormalizer.normalize(question);
        Matcher quantity = QUANTITY_QUESTION.matcher(folded);
        if (!quantity.find()) {
            return null;
        }
        String unit = quantity.group(1);
        return unit.equals("tien") || RagQuestionTerms.contentTerms(unit, language).isEmpty()
                ? null : new RagVietnameseMeasureLookup(unit, MAXIMUM.matcher(folded).find());
    }

    Set<String> values(String clause) {
        String folded = Tokenizer.foldForMatching(clause);
        var values = new LinkedHashSet<String>();
        if (unit == null) {
            clockValues(folded, values);
        } else if (!NON_EXACT.matcher(folded).find() && !APPROXIMATE_WORD.matcher(Tokenizer.nfc(clause)).find()
                && (maximum || !MAXIMUM.matcher(folded).find())) {
            Matcher quantity = QUANTITY.matcher(folded);
            while (quantity.find()) {
                if (unit.equals(quantity.group("unit"))) {
                    String count = quantity.group("count");
                    values.add(Character.isDigit(count.charAt(0))
                            ? count.replaceFirst("^0+(?!$)", "") : Integer.toString(number(count)));
                }
            }
        }
        return values;
    }

    private static void clockValues(String folded, Set<String> values) {
        Matcher clock = CLOCK.matcher(folded);
        while (clock.find()) {
            String spokenHour = clock.group("spokenHour");
            String period = clock.group("period");
            if (spokenHour != null && period == null && clock.group("marker") == null
                    || RELATIVE_PREFIX.matcher(folded).region(0, clock.start()).find()
                    || RELATIVE_SUFFIX.matcher(folded).region(clock.end(), folded.length()).find()) {
                continue;
            }
            int hour = number(spokenHour == null ? clock.group("numericHour") : spokenHour);
            String minutes = spokenHour != null ? clock.group("spokenMinute")
                    : clock.group("colonMinute") != null ? clock.group("colonMinute") : clock.group("hMinute");
            int minute = minutes == null ? 0 : number(minutes);
            if (hour > 23 || minute > 59) {
                continue;
            }
            if (period != null && hour < 12 && Set.of("chieu", "toi", "dem").contains(period)) {
                hour += 12;
            }
            values.add(Integer.toString(hour * 60 + minute));
        }
    }

    private static int number(String text) {
        if (Character.isDigit(text.charAt(0))) {
            return Integer.parseInt(text);
        }
        String[] hundreds = text.split(" tram(?: (?:le|linh))? ?", 2);
        int base = hundreds.length == 2 ? 100 * digit(hundreds[0]) : 0;
        String tail = hundreds[hundreds.length - 1];
        int tens = tail.indexOf("muoi");
        if (tens < 0) {
            return base + digit(tail);
        }
        return base + 10 * (tens == 0 ? 1 : digit(tail.substring(0, tens).strip()))
                + digit(tail.substring(tens + "muoi".length()).strip());
    }

    private static int digit(String word) {
        return switch (word) {
            case "" -> 0;
            case "bon" -> 4;
            case "lam" -> 5;
            default -> DIGITS.indexOf(word);
        };
    }
}
