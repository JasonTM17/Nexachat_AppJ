package com.nexachat.call.core;

/** Immutable server push details needed to safely restore an incoming call. */
public final class IncomingCallPushData {
    private final String callId;
    private final String conversationId;
    private final String callerId;
    private final String callerName;
    private final long serverTs;
    private final String recipientId;
    private final long ringExpiresAtMs;
    private final long sessionEpoch;

    public IncomingCallPushData(
            String callId,
            String conversationId,
            String callerId,
            String callerName,
            long serverTs,
            String recipientId,
            long ringExpiresAtMs,
            long sessionEpoch) {
        this.callId = callId;
        this.conversationId = conversationId;
        this.callerId = callerId;
        this.callerName = callerName;
        this.serverTs = serverTs;
        this.recipientId = recipientId;
        this.ringExpiresAtMs = ringExpiresAtMs;
        this.sessionEpoch = sessionEpoch;
    }

    public String getCallId() { return callId; }
    public String getConversationId() { return conversationId; }
    public String getCallerId() { return callerId; }
    public String getCallerName() { return callerName; }
    public long getServerTs() { return serverTs; }
    public String getRecipientId() { return recipientId; }
    public long getRingExpiresAtMs() { return ringExpiresAtMs; }
    public long getSessionEpoch() { return sessionEpoch; }

    public boolean isValidAt(long nowMs) {
        return hasText(callId)
                && hasText(conversationId)
                && hasText(callerId)
                && hasText(recipientId)
                && serverTs > 0L
                && ringExpiresAtMs > serverTs
                && ringExpiresAtMs > nowMs
                && sessionEpoch >= 0L;
    }

    public boolean matches(CallSession session) {
        return session != null
                && callId.equals(session.getCallId())
                && conversationId.equals(session.getConversationId())
                && callerId.equals(session.getPeerUserId())
                && serverTs == session.getStartedAtMs()
                && session.getDirection() == CallSession.Direction.INCOMING;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
