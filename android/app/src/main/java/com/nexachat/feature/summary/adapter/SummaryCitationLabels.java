package com.nexachat.feature.summary.adapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Label formatting for summary citation chips in Android Views. */
public final class SummaryCitationLabels {

    private SummaryCitationLabels() {
    }

    @NonNull
    public static String messageIdPrefix(@NonNull String messageId) {
        int take = Math.min(4, messageId.length());
        return messageId.substring(0, take);
    }

    /**
     * Formats "Sender · HH:mm" for a cited message, prefixing a short date when the
     * message is not from the same local day as {@code nowEpochMs}. Returns null when
     * the sender is unknown so callers can fall back to the sequence label.
     */
    @Nullable
    public static String senderTime(
            @Nullable String senderDisplayName,
            long createdAtEpochMs,
            long nowEpochMs,
            @NonNull Locale locale
    ) {
        if (senderDisplayName == null || senderDisplayName.trim().isEmpty()
                || createdAtEpochMs <= 0) {
            return null;
        }
        Date sent = new Date(createdAtEpochMs);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(sent);
        String stamp = sameLocalDay(createdAtEpochMs, nowEpochMs)
                ? time
                : DateFormat.getDateInstance(DateFormat.SHORT, locale).format(sent) + " " + time;
        return senderDisplayName.trim() + " · " + stamp;
    }

    private static boolean sameLocalDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(first);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
