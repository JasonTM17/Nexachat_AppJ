package com.nexachat.call;

import java.time.Instant;
import java.util.UUID;

public record CallHistoryItem(
        UUID callId,
        UUID callerId,
        UUID calleeId,
        String state,
        String endReason,
        Instant startedAt,
        Instant answeredAt,
        Instant endedAt,
        Long durationSeconds) {

    public static CallHistoryItem from(CallRecord record) {
        return new CallHistoryItem(record.getCallId(), record.getCallerId(), record.getCalleeId(),
                record.getState().name(), record.getEndReason() == null ? null : record.getEndReason().name(),
                record.getStartedAt(), record.getAnsweredAt(), record.getEndedAt(), record.getDurationSeconds());
    }
}
