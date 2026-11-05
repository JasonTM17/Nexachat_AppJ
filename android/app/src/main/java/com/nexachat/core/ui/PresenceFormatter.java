package com.nexachat.core.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure presentation logic for formatting peer presence subtitles (F3).
 * Written in Java per the project owner language rule.
 */
public final class PresenceFormatter {

    private PresenceFormatter() {}

    /**
     * Formats peer presence into a localized subtitle string.
     *
     * @param online whether the peer is currently online
     * @param lastSeenAtEpochMs epoch ms of last seen instant, or null if hidden/unknown
     * @param nowEpochMs current epoch ms (for deterministic testing)
     * @param activeNowLabel localized "Active now" string
     * @param minutesAgoPattern localized "Active %1$d min ago" pattern
     * @param hoursAgoPattern localized "Active %1$d hr ago" pattern
     * @param yesterdayLabel localized "Active yesterday" string
     * @param dateFormatPattern date pattern for >48h (e.g. "dd/MM")
     * @param locale user locale
     * @return formatted string, or null if presence is hidden/unknown
     */
    public static String formatPresence(
            boolean online,
            Long lastSeenAtEpochMs,
            long nowEpochMs,
            String activeNowLabel,
            String minutesAgoPattern,
            String hoursAgoPattern,
            String yesterdayLabel,
            String dateFormatPattern,
            Locale locale
    ) {
        if (online) {
            return activeNowLabel;
        }
        if (lastSeenAtEpochMs == null || lastSeenAtEpochMs <= 0L) {
            return null;
        }
        long diffMs = nowEpochMs - lastSeenAtEpochMs;
        if (diffMs < 0L) {
            return activeNowLabel;
        }
        long minutes = diffMs / 60_000L;
        if (minutes < 1L) {
            return activeNowLabel;
        }
        if (minutes < 60L) {
            return String.format(locale != null ? locale : Locale.getDefault(), minutesAgoPattern, (int) minutes);
        }
        long hours = minutes / 60L;
        if (hours < 24L) {
            return String.format(locale != null ? locale : Locale.getDefault(), hoursAgoPattern, (int) hours);
        }
        if (hours < 48L) {
            return yesterdayLabel;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(
                (dateFormatPattern != null && !dateFormatPattern.isEmpty()) ? dateFormatPattern : "dd/MM",
                locale != null ? locale : Locale.getDefault()
        );
        return sdf.format(new Date(lastSeenAtEpochMs));
    }
}
