package com.nexachat.streak.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexachat.streak.StreakRules;

import java.time.LocalDate;

/**
 * One streak as the client renders it. Dates are ISO {@code yyyy-MM-dd} in the
 * server's streak zone. {@code mySentToday}/{@code peerSentToday} appear only
 * on a conversation streak and {@code openedToday}/{@code sentToday} only on
 * the check-in streak; the other pair is omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreakResponse(
        int count,
        int bestCount,
        @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate startedOn,
        @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate lastDay,
        LocalDate day,
        boolean atRisk,
        boolean freezeAvailable,
        int milestone,
        Boolean mySentToday,
        Boolean peerSentToday,
        Boolean openedToday,
        Boolean sentToday
) {

    public static StreakResponse conversation(StreakRules.View view, boolean mySentToday,
                                              boolean peerSentToday) {
        return new StreakResponse(view.count(), view.bestCount(), view.startedOn(), view.lastDay(),
                view.day(), view.atRisk(), view.freezeAvailable(), view.milestone(),
                mySentToday, peerSentToday, null, null);
    }

    /** Check-in streak: side A is "opened the app", side B is "sent something". */
    public static StreakResponse checkIn(StreakRules.View view) {
        return new StreakResponse(view.count(), view.bestCount(), view.startedOn(), view.lastDay(),
                view.day(), view.atRisk(), view.freezeAvailable(), view.milestone(),
                null, null, view.sideADoneToday(), view.sideBDoneToday());
    }
}
