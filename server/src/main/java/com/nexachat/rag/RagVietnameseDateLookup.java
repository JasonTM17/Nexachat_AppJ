package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.engine.Tokenizer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct Vietnamese date lookups with an explicit event label and a settled date. */
record RagVietnameseDateLookup(String event) {

    static final String QUESTION_END = "(?:\\s+(?:ạ|a))?\\s*[?!]*\\z";
    private static final Pattern QUESTION = Pattern.compile(
            "\\A(?<event>[^,;?!]+?) (?:là|la) (?:(?:ngày|ngay) (?:nào|nao|mấy|may)"
                    + "|(?:hôm|hom) (?:nào|nao))" + QUESTION_END);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ASSERTION_INTRO = Pattern.compile(
            "\\A(?:(?:tôi|toi|mình|minh|tớ|to|chúng tôi|chung toi) (?:(?:đã|da) )?"
                    + "(?:xác nhận|xac nhan|chốt|chot|thông báo|thong bao)(?: (?:rằng|rang))?(?: *[:,] *| )"
                    + "|(?:thông báo|thong bao|cập nhật|cap nhat|chốt lại|chot lai) *[:,] *"
                    + "|(?:bản tin|ban tin) (?:ghi|xác nhận|xac nhan)(?: (?:rằng|rang))?(?: *[:,] *| )"
                    + "|theo (?:thông báo|thong bao|lịch|lich)(?: mới nhất| moi nhat| chính thức| chinh thuc)? *, *)");
    private static final Pattern OCCURRENCE_LABEL = Pattern.compile(
            "\\A(?:ngày|ngay) (?:diễn ra|dien ra|tổ chức|to chuc) (?:của |cua )?");
    private static final String UNIT = "(?:mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)";
    private static final String WRITTEN_DAY = "(?:ba muoi(?: mot)?|hai muoi(?: " + UNIT + ")?|muoi(?: "
            + UNIT + ")?|" + UNIT + ")";
    private static final String CALENDAR_VALUE = "(?<dateMarker>ngay )?"
            + "(?:(?<weekday>thu (?:hai|ba|tu|nam|sau|bay|[2-7])|chu nhat)"
                    + "|(?<digitDay>[0-9]{1,2})(?<separator>[/.-])(?<numberMonth>[0-9]{1,2})"
                    + "(?:\\k<separator>(?<numberYear>[1-9][0-9]{3}))?(?![/.-][0-9])"
                    + "|(?<wordDay>[0-9]{1,2}|" + WRITTEN_DAY + ") thang"
                    + " (?<wordMonth>muoi(?: mot| hai)?|[a-z]+|[0-9]{1,2})"
                    + "(?: nam (?<wordYear>[1-9][0-9]{3}))?)";
    private static final Pattern DATED_PREDICATE = Pattern.compile(
            "(?<binding> *: *| (?:la|vao) | (?:(?:da )?duoc |da )?(?:chot(?: lai)?|an dinh|xac nhan)(?: la| vao)? "
                    + "| (?:da |se )?(?:duoc )?(?:dien ra|bat dau|ket thuc|to chuc|mo cua|dong cua|len lich)(?: vao)? )"
                    + CALENDAR_VALUE
                    + "(?=$|[.,;:!?]| (?:nhe|nha|ne|nghen|luc|sau|truoc|theo)\\b)");
    private static final Pattern DATE_MENTION = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?<binding>)" + CALENDAR_VALUE + "(?![\\p{L}\\p{N}])");
    private static final Pattern SEPARATE_CLAUSE = Pattern.compile("[;,]\\s*| (?:còn|con|nhưng|nhung|trong khi) ");
    private static final Pattern TEMPORAL_INTRO = Pattern.compile("(?:vào|vao|từ|tu|đến|den)?");
    private static final Pattern OTHER_SUBJECT = Pattern.compile(
            "\\b(?:cua|(?:ket thuc|tong ket|be mac|dien tap|tap duot)(?: cua| cho)?)\\z");
    private static final Pattern UNCERTAIN_TAIL = Pattern.compile(
            "\\b(?:truoc day|truoc kia|ban dau|lich cu|ngay cu|da huy|doi sang|chuyen sang"
                    + "|khong con hieu luc|khong dung nua)\\b"
                    + "|\\b(?:hoac|hay|va|den|toi) (?:ngay )?(?=(?:[0-9]{1,2}(?:[/.-][0-9]| thang )|"
                    + WRITTEN_DAY + " thang |thu |chu nhat))");
    private static final List<String> MONTH_NAMES = List.of(
            "mot", "hai", "ba", "tu", "nam", "sau", "bay", "tam", "chin", "muoi", "muoi mot", "muoi hai");
    private static final List<String> WEEKDAY_NAMES = List.of("hai", "ba", "tu", "nam", "sau", "bay");

    static RagVietnameseDateLookup parse(String question, SummaryLanguage language, boolean namedSpeaker) {
        if (!namedSpeaker || language != SummaryLanguage.VI) {
            return null;
        }
        Matcher matcher = QUESTION.matcher(normalize(question));
        if (!matcher.matches()) {
            return null;
        }
        String event = matcher.group("event").strip();
        return event.codePoints().anyMatch(Character::isLetterOrDigit) ? new RagVietnameseDateLookup(event) : null;
    }

    // Match the predicate immediately after the same event. A date elsewhere in the
    // sentence is not evidence for that event, even when all query terms are present.
    List<String> dates(String statement) {
        String normalized = normalize(statement);
        if (!normalized.startsWith(event)) {
            normalized = ASSERTION_INTRO.matcher(normalized).replaceFirst("");
        }
        if (!normalized.startsWith(event)) {
            normalized = OCCURRENCE_LABEL.matcher(normalized).replaceFirst("");
        }
        if (!normalized.startsWith(event)) {
            return List.of();
        }
        String predicate = Tokenizer.foldForMatching(normalized.substring(event.length()));
        Matcher matcher = DATED_PREDICATE.matcher(predicate);
        if (!matcher.lookingAt()) {
            return List.of();
        }
        String tail = predicate.substring(matcher.end());
        if (UNCERTAIN_TAIL.matcher(tail).find()) {
            return List.of();
        }
        String date = calendarValue(matcher);
        if (date == null) {
            return List.of();
        }
        String otherDate = conflictingDateForEvent(tail, date);
        return otherDate == null ? List.of(date) : List.of(date, otherDate);
    }

    DateEvidence inspect(String statement) {
        List<String> direct = dates(statement);
        String normalized = normalize(statement);
        if (direct.size() > 1) {
            return new DateEvidence(direct, false, false);
        }
        var compatible = new LinkedHashSet<>(direct);
        boolean otherDatedAssertion = false;
        boolean invalidDate = false;
        for (String clause : SEPARATE_CLAUSE.split(normalized)) {
            int eventStart = eventStart(clause);
            boolean target = eventStart >= 0 && !OTHER_SUBJECT.matcher(
                    Tokenizer.foldForMatching(clause.substring(0, eventStart)).strip()).find();
            String folded = Tokenizer.foldForMatching(clause);
            Matcher dates = DATE_MENTION.matcher(folded);
            while (dates.find()) {
                String value = calendarValue(dates);
                if (value == null) {
                    invalidDate |= target && (dates.group("dateMarker") != null || "/".equals(dates.group("separator")));
                } else if (target) {
                    compatible.add(value);
                } else if (!TEMPORAL_INTRO.matcher(folded.substring(0, dates.start()).strip()).matches()) {
                    otherDatedAssertion = true;
                }
            }
        }
        // Preserve ordinary phrasing when its attachment is uncertain. Reject a date
        // asserted only for a different subject, or an invalid date stated for this one.
        boolean disproven = compatible.isEmpty() && (invalidDate || otherDatedAssertion && eventStart(normalized) >= 0);
        return new DateEvidence(List.copyOf(compatible), disproven, direct.size() == 1);
    }

    private int eventStart(String text) {
        int start = text.indexOf(event);
        while (start >= 0) {
            int end = start + event.length();
            if ((start == 0 || !Character.isLetterOrDigit(text.codePointBefore(start)))
                    && (end == text.length() || !Character.isLetterOrDigit(text.codePointAt(end)))) {
                return start;
            }
            start = text.indexOf(event, end);
        }
        return -1;
    }

    record DateEvidence(List<String> values, boolean disproven, boolean directlyBound) {
        boolean usable() {
            return !disproven && values.size() < 2;
        }
    }

    private static String calendarValue(Matcher matcher) {
        String weekday = matcher.group("weekday");
        if (weekday != null) {
            if (weekday.equals("chu nhat")) {
                return "weekday:8";
            }
            String name = weekday.substring("thu ".length());
            int number = Character.isDigit(name.charAt(0)) ? Integer.parseInt(name) : WEEKDAY_NAMES.indexOf(name) + 2;
            return "weekday:" + number;
        }

        boolean numericDate = matcher.group("digitDay") != null;
        int day = numericDate ? Integer.parseInt(matcher.group("digitDay")) : calendarDay(matcher.group("wordDay"));
        String month = matcher.group(numericDate ? "numberMonth" : "wordMonth");
        String year = matcher.group(numericDate ? "numberYear" : "wordYear");
        String binding = matcher.group("binding").strip();
        if (numericDate && !"/".equals(matcher.group("separator")) && year == null && matcher.group("dateMarker") == null
                && (binding.isEmpty() || binding.equals("la") || binding.equals(":"))) {
            return null;
        }
        int monthNumber = switch (month) {
            case "gieng" -> 1;
            case "chap" -> 12;
            case "bon" -> 4;
            default -> Character.isDigit(month.charAt(0)) ? Integer.parseInt(month) : MONTH_NAMES.indexOf(month) + 1;
        };
        try {
            return year == null ? MonthDay.of(monthNumber, day).toString()
                    : LocalDate.of(Integer.parseInt(year), monthNumber, day).toString();
        } catch (DateTimeException invalidDate) {
            return null;
        }
    }

    private String conflictingDateForEvent(String tail, String firstDate) {
        String foldedEvent = Tokenizer.foldForMatching(event);
        int offset = tail.indexOf(foldedEvent);
        while (offset != -1) {
            Matcher matcher = DATED_PREDICATE.matcher(tail).region(offset + foldedEvent.length(), tail.length());
            if ((offset == 0 || !Character.isLetterOrDigit(tail.charAt(offset - 1))) && matcher.lookingAt()) {
                String otherDate = calendarValue(matcher);
                if (otherDate != null && !firstDate.equals(otherDate)) {
                    return otherDate;
                }
            }
            offset = tail.indexOf(foldedEvent, offset + foldedEvent.length());
        }
        return null;
    }

    private static int calendarDay(String day) {
        if (Character.isDigit(day.charAt(0))) {
            return Integer.parseInt(day);
        }
        int tens = day.indexOf("muoi");
        if (tens < 0) {
            return unit(day);
        }
        int base = tens == 0 ? 10 : 10 * unit(day.substring(0, tens).strip());
        return base + unit(day.substring(tens + "muoi".length()).strip());
    }

    private static int unit(String name) {
        return switch (name) {
            case "" -> 0;
            case "bon" -> 4;
            case "lam" -> 5;
            default -> MONTH_NAMES.indexOf(name) + 1;
        };
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(Tokenizer.nfc(text).strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}
