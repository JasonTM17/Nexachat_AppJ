package com.nexachat.call;

import java.time.Instant;
import java.util.UUID;

public record CallEvent(
        String eventType,
        UUID callId,
        UUID conversationId,
        UUID fromUserId,
        Instant serverTs,
        Payload payload) {

    public record Payload(
            String type,
            UUID callId,
            CallSignal.Sdp sdp,
            CallSignal.Candidate candidate,
            String reason,
            Long durationSeconds) {
    }
}
