package com.nexachat.call;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CallSession(
        UUID callId,
        UUID conversationId,
        UUID callerId,
        UUID calleeId,
        CallState state,
        CallEndReason endReason,
        Instant startedAt,
        Instant answeredAt,
        Instant endedAt,
        Long durationSeconds) {

    public CallSession {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(calleeId, "calleeId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        if (callerId.equals(calleeId)) {
            throw new IllegalArgumentException("Call participants must be different");
        }
        if (state == CallState.ENDED) {
            if (endReason == null || endedAt == null || durationSeconds == null || durationSeconds < 0) {
                throw new IllegalArgumentException("Ended calls require terminal details");
            }
        } else if (endReason != null || endedAt != null || durationSeconds != null) {
            throw new IllegalArgumentException("Non-terminal calls cannot have terminal details");
        }
        if (state == CallState.ACTIVE && answeredAt == null) {
            throw new IllegalArgumentException("Active calls require an answer time");
        }
    }

    public static CallSession ringing(UUID callId, UUID conversationId, UUID callerId,
                                      UUID calleeId, Instant startedAt) {
        return new CallSession(callId, conversationId, callerId, calleeId,
                CallState.RINGING, null, startedAt, null, null, null);
    }

    public UUID peerOf(UUID userId) {
        if (callerId.equals(userId)) {
            return calleeId;
        }
        if (calleeId.equals(userId)) {
            return callerId;
        }
        return null;
    }
}
