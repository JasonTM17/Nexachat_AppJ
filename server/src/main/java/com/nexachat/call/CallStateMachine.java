package com.nexachat.call;

import java.time.Duration;
import java.time.Instant;

public final class CallStateMachine {

    private CallStateMachine() {
    }

    public static CallSession accept(CallSession session, Instant now) {
        if (session.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        return new CallSession(session.callId(), session.conversationId(), session.callerId(), session.calleeId(),
                CallState.ACTIVE, null, session.startedAt(), now, null, null);
    }

    public static CallSession end(CallSession session, CallEndReason reason, Instant now) {
        if (session.state() == CallState.ENDED || !allowsEnd(session.state(), reason)) {
            throw CallException.invalidState();
        }
        long duration = session.answeredAt() == null
                ? 0L
                : Math.max(0L, Duration.between(session.answeredAt(), now).getSeconds());
        return new CallSession(session.callId(), session.conversationId(), session.callerId(), session.calleeId(),
                CallState.ENDED, reason, session.startedAt(), session.answeredAt(), now, duration);
    }

    private static boolean allowsEnd(CallState state, CallEndReason reason) {
        if (reason == null) {
            return false;
        }
        return switch (state) {
            case RINGING -> reason == CallEndReason.REJECTED
                    || reason == CallEndReason.CANCELLED
                    || reason == CallEndReason.MISSED
                    || reason == CallEndReason.BUSY
                    || reason == CallEndReason.FAILED;
            case ACTIVE -> reason == CallEndReason.COMPLETED || reason == CallEndReason.FAILED;
            case ENDED -> false;
        };
    }
}
