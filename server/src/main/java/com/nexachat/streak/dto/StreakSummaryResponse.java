package com.nexachat.streak.dto;

/** {@code GET /streaks/me} and {@code POST /streaks/check-in}. */
public record StreakSummaryResponse(StreakResponse checkIn, boolean reminderEnabled) {
}
