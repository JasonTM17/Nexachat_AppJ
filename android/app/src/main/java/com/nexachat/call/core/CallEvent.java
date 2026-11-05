package com.nexachat.call.core;

/** Immutable server event decoded from the authenticated call queue. */
public final class CallEvent {
    public enum Type {
        CALL_INVITE,
        CALL_ACCEPTED,
        CALL_REJECTED,
        CALL_CANCELLED,
        CALL_ENDED,
        CALL_BUSY,
        CALL_TIMEOUT,
        CALL_SDP,
        CALL_ICE,
        CALL_ERROR,
        UNKNOWN
    }

    private final Type type;
    private final String wireType;
    private final String callId;
    private final String conversationId;
    private final String fromUserId;
    private final String serverTs;
    private final CallSignal signal;
    private final String reason;
    private final Integer durationSeconds;
    private final String errorCode;

    public CallEvent(
            Type type,
            String wireType,
            String callId,
            String conversationId,
            String fromUserId,
            String serverTs,
            CallSignal signal,
            String reason,
            Integer durationSeconds,
            String errorCode) {
        this.type = type == null ? Type.UNKNOWN : type;
        this.wireType = wireType;
        this.callId = callId;
        this.conversationId = conversationId;
        this.fromUserId = fromUserId;
        this.serverTs = serverTs;
        this.signal = signal;
        this.reason = reason;
        this.durationSeconds = durationSeconds;
        this.errorCode = errorCode;
    }

    public Type getType() { return type; }
    public String getWireType() { return wireType; }
    public String getCallId() { return callId; }
    public String getConversationId() { return conversationId; }
    public String getFromUserId() { return fromUserId; }
    public String getServerTs() { return serverTs; }
    public CallSignal getSignal() { return signal; }
    public String getReason() { return reason; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public String getErrorCode() { return errorCode; }
}
