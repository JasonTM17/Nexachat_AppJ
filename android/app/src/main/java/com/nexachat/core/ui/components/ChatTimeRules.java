package com.nexachat.core.ui.components;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Calendar and clock rules for chat date separators and relative timestamps.
 * Shared by the chat presentation helpers.
 */
public final class ChatTimeRules {

    private ChatTimeRules() {
    }

    /**
     * First message in thread ({@code prevMessageEpochMs == null}) or different calendar day.
     */
    public static boolean shouldShowDateSeparator(Long prevMessageEpochMs, long currentMessageEpochMs) {
        if (prevMessageEpochMs == null) {
            return true;
        }
        Calendar cal1 = Calendar.getInstance();
        cal1.setTimeInMillis(prevMessageEpochMs);
        Calendar cal2 = Calendar.getInstance();
        cal2.setTimeInMillis(currentMessageEpochMs);
        return cal1.get(Calendar.YEAR) != cal2.get(Calendar.YEAR)
                || cal1.get(Calendar.DAY_OF_YEAR) != cal2.get(Calendar.DAY_OF_YEAR);
    }

    public static String resolveDateSeparatorLabel(
            long messageEpochMs,
            long nowEpochMs,
            DateSeparatorLabels labels,
            Locale locale
    ) {
        Calendar calMsg = Calendar.getInstance();
        calMsg.setTimeInMillis(messageEpochMs);
        Calendar calNow = Calendar.getInstance();
        calNow.setTimeInMillis(nowEpochMs);

        int msgYear = calMsg.get(Calendar.YEAR);
        int nowYear = calNow.get(Calendar.YEAR);
        int msgDay = calMsg.get(Calendar.DAY_OF_YEAR);
        int nowDay = calNow.get(Calendar.DAY_OF_YEAR);

        boolean isToday = msgYear == nowYear && msgDay == nowDay;
        boolean isYesterday = (msgYear == nowYear && msgDay == nowDay - 1)
                || (msgYear == nowYear - 1 && nowDay == 1
                && msgDay == calMsg.getActualMaximum(Calendar.DAY_OF_YEAR));

        if (isToday) {
            return labels.getToday();
        }
        if (isYesterday) {
            return labels.getYesterday();
        }
        if (msgYear == nowYear) {
            return new SimpleDateFormat("dd/MM", locale).format(new Date(messageEpochMs));
        }
        return new SimpleDateFormat("dd/MM/yyyy", locale).format(new Date(messageEpochMs));
    }

    public static String resolveDateSeparatorLabel(
            long messageEpochMs,
            long nowEpochMs,
            DateSeparatorLabels labels
    ) {
        return resolveDateSeparatorLabel(messageEpochMs, nowEpochMs, labels, Locale.getDefault());
    }

    public static String resolveRelativeTimeLabel(
            long epochMs,
            long nowEpochMs,
            String justNowLabel,
            String yesterdayLabel,
            Locale locale
    ) {
        long diff = Math.max(0L, nowEpochMs - epochMs);
        Calendar calMsg = Calendar.getInstance();
        calMsg.setTimeInMillis(epochMs);
        Calendar calNow = Calendar.getInstance();
        calNow.setTimeInMillis(nowEpochMs);

        int msgYear = calMsg.get(Calendar.YEAR);
        int nowYear = calNow.get(Calendar.YEAR);
        int msgDay = calMsg.get(Calendar.DAY_OF_YEAR);
        int nowDay = calNow.get(Calendar.DAY_OF_YEAR);

        boolean isToday = msgYear == nowYear && msgDay == nowDay;
        boolean isYesterday = (msgYear == nowYear && msgDay == nowDay - 1)
                || (msgYear == nowYear - 1 && nowDay == 1
                && msgDay == calMsg.getActualMaximum(Calendar.DAY_OF_YEAR));

        if (diff < 60_000L) {
            return justNowLabel;
        }
        if (isToday) {
            return new SimpleDateFormat("HH:mm", locale).format(new Date(epochMs));
        }
        if (isYesterday) {
            return yesterdayLabel;
        }
        if (msgYear == nowYear) {
            return new SimpleDateFormat("dd/MM", locale).format(new Date(epochMs));
        }
        return new SimpleDateFormat("dd/MM/yy", locale).format(new Date(epochMs));
    }

    public static String resolveRelativeTimeLabel(
            long epochMs,
            long nowEpochMs,
            String justNowLabel,
            String yesterdayLabel
    ) {
        return resolveRelativeTimeLabel(epochMs, nowEpochMs, justNowLabel, yesterdayLabel, Locale.getDefault());
    }
}
