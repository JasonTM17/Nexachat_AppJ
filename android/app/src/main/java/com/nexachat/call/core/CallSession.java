package com.nexachat.call.core;

/** Immutable snapshot consumed by the call UI. */
public final class CallSession {
    public enum Direction {
        OUTGOING,
        INCOMING
    }

    private final String callId;
    private final String conversationId;
    private final String peerUserId;
    private final String peerDisplayName;
    private final Direction direction;
    private final CallState state;
    private final CallEndReason endReason;
    private final long startedAtMs;
    private final Long answeredAtMs;
    private final boolean muted;
    private final boolean speakerOn;

    public CallSession(
            String callId,
            String conversationId,
            String peerUserId,
            String peerDisplayName,
            Direction direction,
            CallState state,
            CallEndReason endReason,
            long startedAtMs,
            Long answeredAtMs,
            boolean muted,
            boolean speakerOn) {
        this.callId = callId;
        this.conversationId = conversationId;
        this.peerUserId = peerUserId;
        this.peerDisplayName = peerDisplayName;
        this.direction = direction;
        this.state = state == null ? CallState.IDLE : state;
        this.endReason = endReason;
        this.startedAtMs = startedAtMs;
        this.answeredAtMs = answeredAtMs;
        this.muted = muted;
        this.speakerOn = speakerOn;
    }

    public static CallSession idle() {
        return new CallSession(
                null, null, null, null, null, CallState.IDLE, null, 0L, null, false, false);
    }

    public String getCallId() { return callId; }
    public String getConversationId() { return conversationId; }
    public String getPeerUserId() { return peerUserId; }
    public String getPeerDisplayName() { return peerDisplayName; }
    public Direction getDirection() { return direction; }
    public CallState getState() { return state; }
    public CallEndReason getEndReason() { return endReason; }
    public long getStartedAtMs() { return startedAtMs; }
    public Long getAnsweredAtMs() { return answeredAtMs; }
    public boolean isMuted() { return muted; }
    public boolean isSpeakerOn() { return speakerOn; }

    CallSession withState(CallState newState, CallEndReason reason, Long answerTimeMs) {
        return new CallSession(
                callId,
                conversationId,
                peerUserId,
                peerDisplayName,
                direction,
                newState,
                reason,
                startedAtMs,
                answerTimeMs,
                muted,
                speakerOn);
    }

    CallSession withAudio(boolean newMuted, boolean newSpeakerOn) {
        return new CallSession(
                callId,
                conversationId,
                peerUserId,
                peerDisplayName,
                direction,
                state,
                endReason,
                startedAtMs,
                answeredAtMs,
                newMuted,
                newSpeakerOn);
    }
}
