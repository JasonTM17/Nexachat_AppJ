package com.nexachat.feature.streak;

import androidx.annotation.Nullable;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Pure client-side streak rules shared by the screens, the check-in job and tests. */
public final class StreakRules {

    /** Streak lengths worth a celebration, ascending. */
    public static final int[] MILESTONES = {3, 7, 30, 100};

    private StreakRules() { }

    /** A flame appears from the second consecutive day. */
    public static boolean isBadgeVisible(int count) {
        return count >= 2;
    }

    public static boolean isMilestone(int value) {
        for (int milestone : MILESTONES) {
            if (milestone == value) {
                return true;
            }
        }
        return false;
    }

    /** Identity of one celebration: a milestone reached by one chain of one scope. */
    public static String celebrationKey(String scope, @Nullable String startedOn, int milestone) {
        return scope + "|" + (startedOn == null ? "" : startedOn) + "|" + milestone;
    }

    /**
     * Whether the daily check-in still has to be sent. The stored value is the device-local
     * day of the last success; a stored day that is today or later means today is done.
     */
    public static boolean shouldCheckIn(@Nullable String lastCheckInDay, LocalDate today) {
        if (lastCheckInDay == null || lastCheckInDay.isBlank()) {
            return true;
        }
        try {
            return LocalDate.parse(lastCheckInDay).isBefore(today);
        } catch (DateTimeParseException malformed) {
            return true;
        }
    }

    /**
     * After a chat message the conversation streak can only change until both sides have sent
     * today, so a refresh is useful only while that is not yet known to be true.
     */
    public static boolean needsRefreshAfterActivity(@Nullable StreakDto current) {
        return current == null || !(current.isMySentToday() && current.isPeerSentToday());
    }
}
