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

    static final String QUESTION_END =
            "(?:\\s+(?:ạ|á|a|vậy|vay|nhỉ|nhi|rồi|roi|ta|thế|the|nhé|nhe|nha)){0,3}\\s*[?!]*\\z";
    private static final String DATE_REQUEST =
            "(?:(?:ngày|ngay) (?:nào|nao|mấy|may|bao nhiêu|bao nhieu)"
                    + "|(?:hôm|hom) (?:nào|nao)|(?:mùng|mung|mồng|mong) (?:mấy|may))";
    private static final String SHIFT = "(?:dời|đổi|chuyển|doi|chuyen) (?:sang|qua)";
    private static final Pattern QUESTION = Pattern.compile(
            "\\A(?<event>[^,;?!]+?) (?:(?:là|la|vào|vao|chốt|chot|diễn ra|dien ra|" + SHIFT + ") )?"
                    + DATE_REQUEST + QUESTION_END);
    private static final Pattern DATE_CUE = Pattern.compile("(?<![\\p{L}\\p{N}])" + DATE_REQUEST + QUESTION_END);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ASSERTION_INTRO = Pattern.compile(
            "\\A(?:(?:tôi|toi|mình|minh|tớ|to|chúng tôi|chung toi) (?:(?:đã|da) )?"
                    + "(?:xác nhận|xac nhan|chốt|chot|thông báo|thong bao)(?: (?:rằng|rang))?(?: *[:,] *| )"
                    + "|(?:thông báo|thong bao|cập nhật|cap nhat|chốt lại|chot lai)(?: nhé| nhe| nha)? *[:,] *"
                    + "|(?:bản tin|ban tin) (?:ghi|xác nhận|xac nhan)(?: (?:rằng|rang))?(?: *[:,] *| )"
                    + "|theo (?:thông báo|thong bao|lịch|lich)(?: mới nhất| moi nhat| chính thức| chinh thuc)? *, *)");
    private static final Pattern OCCURRENCE_LABEL = Pattern.compile(
            "\\A(?:ngày|ngay) (?:diễn ra|dien ra|tổ chức|to chuc) (?:của |cua )?");
    private static final Pattern DATE_STATUS_SUBJECT = Pattern.compile(
            "\\A(?:lịch|lich) (?:của |cua )?");
    private static final Pattern EVENT_DATE_UNSET = Pattern.compile(
            "\\A (?:(?:thi|van|hien tai|van con) )?chua (?:(?:duoc|the|co the) )?"
                    + "(?:chot|an dinh|xac nhan|xac dinh|thong bao|cong bo)"
                    + "(?: (?:ngay(?: gio)?|thoi diem)(?: cu the)?)?"
                    + "(?: (?:chinh thuc|cuoi cung|dau))?[.!]?\\z");
    private static final String UNIT = "(?:mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)";
    private static final String WRITTEN_DAY = "(?:ba muoi(?: mot)?|hai muoi(?: " + UNIT + ")?|muoi(?: "
            + UNIT + ")?|" + UNIT + ")";
    private static final String CALENDAR_VALUE = "(?<dateMarker>(?:ngay|mung|mong) )?"
            + "(?:(?<weekday>thu (?:hai|ba|tu|nam|sau|bay|[2-7])|chu nhat)"
                    + "|(?<digitDay>[0-9]{1,2})(?<separator>[/.-])(?<numberMonth>[0-9]{1,2})"
                    + "(?:\\k<separator>(?<numberYear>[1-9][0-9]{3}))?(?![/.-][0-9])"
                    + "|(?<wordDay>[0-9]{1,2}|" + WRITTEN_DAY + ") thang"
                    + " (?<wordMonth>muoi(?: mot| hai)?|[a-z]+|[0-9]{1,2})"
                    + "(?: nam (?<wordYear>[1-9][0-9]{3}))?)";
    private static final Pattern DATED_PREDICATE = Pattern.compile(
            "(?<binding> *: *| (?:la|vao) | (?:(?:da )?duoc |da )?(?:chot(?: lai)?|an dinh|xac nhan)(?: la| vao)? "
                    + "| (?:da |se )?(?:duoc )?(?:doi|chuyen) (?:sang|qua) "
                    + "| (?:da |se )?(?:duoc )?(?:dien ra|bat dau|ket thuc|to chuc|mo cua|dong cua|len lich)(?: vao)? )"
                    + CALENDAR_VALUE
                    + "(?=$|[.,;:!?]| (?:nhe|nha|ne|nghen|luc|sau|truoc|theo)\\b)");
    private static final Pattern DATE_MENTION = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?<binding>)" + CALENDAR_VALUE + "(?![\\p{L}\\p{N}])");
    private static final Pattern WAITS_UNTIL = Pattern.compile(" (?:đã |sẽ )?(?:được )?(?:đợi|chờ) (?:sang|qua) ");
    private static final Pattern SEPARATE_CLAUSE = Pattern.compile("[;,]\\s*| (?:còn|con|nhưng|nhung|trong khi) ");
    private static final Pattern OTHER_SUBJECT = Pattern.compile(
            "\\b(?:cua|(?:ket thuc|tong ket|be mac|dien tap|tap duot)(?: cua| cho)?)\\z");
    private static final String RESERVATION = "dat(?: truoc)? (?:phong|hoi truong|sanh|dia diem|cho)\\b[^,;:?!]*? cho\\z";
    private static final Pattern RESERVATION_MENTION = Pattern.compile("\\b" + RESERVATION);
    private static final Pattern COMPLETED_RESERVATION = Pattern.compile(
            "\\A(?:toi|minh|to|chung toi|chung minh) da " + RESERVATION);
    private static final Pattern RESERVATION_SCHEDULE = Pattern.compile(
            "\\A\\s*[,;]\\s*(?:(?:ai ranh thi|moi nguoi|cac ban|ca nhom) )?(?:nho )?"
                    + "(?:giu|ghi|luu) lich(?: giup (?:toi|minh|to|chung toi))?(?: nhe| nha)?[.!]?\\z");
    private static final Pattern UNCERTAIN_TAIL = Pattern.compile(
            "\\b(?:truoc day|truoc kia|ban dau|lich cu|ngay cu|da huy|doi sang|chuyen sang"
                    + "|khong con hieu luc|khong dung nua)\\b"
                    + "|\\b(?:hoac|hay|va|den|toi) (?:ngay )?(?=(?:[0-9]{1,2}(?:[/.-][0-9]| thang )|"
                    + WRITTEN_DAY + " thang |thu |chu nhat))");
    private static final List<String> MONTH_NAMES = List.of(
            "mot", "hai", "ba", "tu", "nam", "sau", "bay", "tam", "chin", "muoi", "muoi mot", "muoi hai");
    private static final List<String> WEEKDAY_NAMES = List.of("hai", "ba", "tu", "nam", "sau", "bay");

    static boolean isDateQuestion(String question) {
        return DATE_CUE.matcher(normalize(question)).find();
    }

    static RagVietnameseDateLookup parse(String question, SummaryLanguage language) {
        if (language != SummaryLanguage.VI) {
            return null;
        }
        Matcher matcher = QUESTION.matcher(normalize(question));
        if (!matcher.matches()) {
            return null;
        }
        String event = OCCURRENCE_LABEL.matcher(matcher.group("event")).replaceFirst("").strip();
        return event.codePoints().anyMatch(Character::isLetterOrDigit) ? new RagVietnameseDateLookup(event) : null;
    }

    // Match the predicate immediately after the same event. A date elsewhere in the
    // sentence is not evidence for that event, even when all query terms are present.
    private DateEvidence directDate(String statement) {
        String normalized = normalize(statement);
        if (!normalized.startsWith(event)) {
            normalized = ASSERTION_INTRO.matcher(normalized).replaceFirst("");
        }
        if (!normalized.startsWith(event)) {
            normalized = OCCURRENCE_LABEL.matcher(normalized).replaceFirst("");
        }
        boolean reservation = false;
        if (!normalized.startsWith(event)) {
            int start = eventStart(normalized);
            if (start < 0) {
                return null;
            }
            String prefix = Tokenizer.foldForMatching(normalized.substring(0, start)).strip();
            if (!COMPLETED_RESERVATION.matcher(prefix).matches() || DATE_MENTION.matcher(prefix).find()) {
                return null;
            }
            normalized = normalized.substring(start);
            reservation = true;
        }
        String originalPredicate = normalized.substring(event.length());
        if (WAITS_UNTIL.matcher(originalPredicate).lookingAt()) {
            return new DateEvidence(List.of(), false);
        }
        String predicate = Tokenizer.foldForMatching(originalPredicate);
        Matcher matcher = DATED_PREDICATE.matcher(predicate);
        if (!matcher.lookingAt()) {
            return null;
        }
        String tail = predicate.substring(matcher.end());
        if (UNCERTAIN_TAIL.matcher(tail).find()
                || reservation && (!"vao".equals(matcher.group("binding").strip())
                    || !RESERVATION_SCHEDULE.matcher(tail).matches())) {
            return new DateEvidence(List.of(), false);
        }
        String date = calendarValue(matcher);
        if (date == null) {
            return new DateEvidence(List.of(), false);
        }
        String otherDate = conflictingDateForEvent(tail, date);
        return otherDate == null ? new DateEvidence(List.of(date), true)
                : new DateEvidence(List.of(date, otherDate), false);
    }

    DateEvidence inspect(String statement) {
        DateEvidence direct = directDate(statement);
        String normalized = normalize(statement);
        if (direct != null && !direct.usable()) {
            // A recognized but unconfirmed, stale, invalid or conflicting date cannot be
            // rescued by the looser calendar-mention fallback.
            return direct;
        }
        var compatible = new LinkedHashSet<String>();
        if (direct != null) {
            compatible.addAll(direct.values());
        }
        for (String clause : SEPARATE_CLAUSE.split(normalized)) {
            int eventStart = eventStart(clause);
            if (eventStart < 0) {
                continue;
            }
            String prefix = Tokenizer.foldForMatching(clause.substring(0, eventStart)).strip();
            // A reservation can be dated independently of the event. Only the explicit
            // scheduling form checked by dates() supplies an event date in that clause.
            if (OTHER_SUBJECT.matcher(prefix).find() || RESERVATION_MENTION.matcher(prefix).find()
                    || WAITS_UNTIL.matcher(clause.substring(eventStart + event.length())).lookingAt()) {
                continue;
            }
            Matcher dates = DATE_MENTION.matcher(Tokenizer.foldForMatching(clause));
            while (dates.find()) {
                String value = calendarValue(dates);
                if (value != null) {
                    compatible.add(value);
                }
            }
        }
        return new DateEvidence(List.copyOf(compatible), direct != null);
    }

    boolean statesDateIsUnsettled(String statement) {
        String text = ASSERTION_INTRO.matcher(normalize(statement)).replaceFirst("");
        if (eventStart(text) == 0) {
            return EVENT_DATE_UNSET.matcher(Tokenizer.foldForMatching(text.substring(event.length()))).matches();
        }
        Matcher subject = OCCURRENCE_LABEL.matcher(text);
        if (!subject.lookingAt()) {
            subject = DATE_STATUS_SUBJECT.matcher(text);
            if (!subject.lookingAt()) {
                return false;
            }
        }
        String assertion = text.substring(subject.end());
        return eventStart(assertion) == 0 && EVENT_DATE_UNSET.matcher(
                Tokenizer.foldForMatching(assertion.substring(event.length()))).lookingAt();
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

    record DateEvidence(List<String> values, boolean directlyBound) {
        boolean usable() {
            return values.size() == 1;
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
