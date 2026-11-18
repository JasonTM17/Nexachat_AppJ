package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Streak payload shared by the conversation, check-in and "me" endpoints. Moshi binds this
 * reflective bean; every field is optional so older and newer servers both parse. The "me" and
 * check-in responses wrap the check-in streak in {@code checkIn}; {@link #checkInView()} reads
 * either shape.
 */
public final class StreakDto {
    @Nullable private Integer count;
    @Nullable private Integer bestCount;
    @Nullable private String startedOn;
    @Nullable private String lastDay;
    @Nullable private String day;
    @Nullable private Boolean atRisk;
    @Nullable private Boolean freezeAvailable;
    @Nullable private Integer milestone;
    @Nullable private Boolean mySentToday;
    @Nullable private Boolean peerSentToday;
    @Nullable private Boolean openedToday;
    @Nullable private Boolean sentToday;
    @Nullable private StreakDto checkIn;
    @Nullable private Boolean reminderEnabled;

    public StreakDto() { }

    public StreakDto(
            int count,
            int bestCount,
            @Nullable String startedOn,
            @Nullable String day,
            boolean atRisk,
            int milestone,
            boolean mySentToday,
            boolean peerSentToday) {
        this.count = count;
        this.bestCount = bestCount;
        this.startedOn = startedOn;
        this.day = day;
        this.atRisk = atRisk;
        this.milestone = milestone;
        this.mySentToday = mySentToday;
        this.peerSentToday = peerSentToday;
    }

    public int getCount() { return count == null ? 0 : Math.max(0, count); }

    public int getBestCount() { return bestCount == null ? 0 : Math.max(0, bestCount); }

    @Nullable public String getStartedOn() { return startedOn; }

    @Nullable public String getLastDay() { return lastDay; }

    /** The server's current streak day, ISO {@code yyyy-MM-dd}. */
    @Nullable public String getDay() { return day; }

    /** Alive but not completed today; never true for a zero count. */
    public boolean isAtRisk() { return getCount() > 0 && Boolean.TRUE.equals(atRisk); }

    public boolean isFreezeAvailable() { return Boolean.TRUE.equals(freezeAvailable); }

    public int getMilestone() { return milestone == null ? 0 : Math.max(0, milestone); }

    public boolean isMySentToday() { return Boolean.TRUE.equals(mySentToday); }

    public boolean isPeerSentToday() { return Boolean.TRUE.equals(peerSentToday); }

    public boolean isOpenedToday() { return Boolean.TRUE.equals(openedToday); }

    public boolean isSentToday() { return Boolean.TRUE.equals(sentToday); }

    public boolean isReminderEnabled() { return Boolean.TRUE.equals(reminderEnabled); }

    /** The check-in streak of a "me" or check-in response, or this object when it is the bare shape. */
    @NonNull
    public StreakDto checkInView() {
        return checkIn != null ? checkIn : this;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StreakDto that)) {
            return false;
        }
        return getCount() == that.getCount()
                && getBestCount() == that.getBestCount()
                && Objects.equals(startedOn, that.startedOn)
                && Objects.equals(lastDay, that.lastDay)
                && Objects.equals(day, that.day)
                && isAtRisk() == that.isAtRisk()
                && isFreezeAvailable() == that.isFreezeAvailable()
                && getMilestone() == that.getMilestone()
                && isMySentToday() == that.isMySentToday()
                && isPeerSentToday() == that.isPeerSentToday()
                && isOpenedToday() == that.isOpenedToday()
                && isSentToday() == that.isSentToday()
                && Objects.equals(checkIn, that.checkIn)
                && isReminderEnabled() == that.isReminderEnabled();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCount(), getBestCount(), startedOn, lastDay, day, isAtRisk(),
                getMilestone(), isMySentToday(), isPeerSentToday(), checkIn);
    }
}
