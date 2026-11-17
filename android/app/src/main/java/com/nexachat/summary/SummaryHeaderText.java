package com.nexachat.summary;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Builds the one-line result header: scope, local day, zone and processor. */
public final class SummaryHeaderText {
    private static final String SEPARATOR = " · ";

    private SummaryHeaderText() {
    }

    /**
     * Joins the non-empty parts with a middle dot, for example
     * {@code Today · 17 Nov · Asia/Ho_Chi_Minh · Local}.
     */
    public static String format(
            String scopeLabel,
            String dateLabel,
            String timeZone,
            String processorLabel) {
        StringBuilder header = new StringBuilder();
        append(header, scopeLabel);
        append(header, dateLabel);
        append(header, timeZone);
        append(header, processorLabel);
        return header.toString();
    }

    /** Formats an ISO local date as a short day and month, or null when it cannot be read. */
    public static String dateLabel(String isoDate, Locale locale) {
        if (isoDate == null || isoDate.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.trim())
                    .format(DateTimeFormatter.ofPattern("d MMM",
                            locale == null ? Locale.getDefault() : locale));
        } catch (RuntimeException invalidDate) {
            return null;
        }
    }

    private static void append(StringBuilder header, String part) {
        if (part == null || part.trim().isEmpty()) {
            return;
        }
        if (header.length() > 0) {
            header.append(SEPARATOR);
        }
        header.append(part.trim());
    }
}
