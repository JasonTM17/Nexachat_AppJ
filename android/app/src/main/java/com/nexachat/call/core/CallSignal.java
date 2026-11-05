package com.nexachat.call.core;

/** Client-to-server call frame. The wire codec always emits all four fields. */
public final class CallSignal {
    public enum Type {
        INVITE,
        ACCEPT,
        REJECT,
        CANCEL,
        END,
        SDP,
        ICE
    }

    public static final class Sdp {
        private final String type;
        private final String description;

        public Sdp(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() { return type; }
        public String getDescription() { return description; }
    }

    public static final class IceCandidate {
        private final String sdpMid;
        private final Integer sdpMLineIndex;
        private final String candidate;

        public IceCandidate(String sdpMid, Integer sdpMLineIndex, String candidate) {
            this.sdpMid = sdpMid;
            this.sdpMLineIndex = sdpMLineIndex;
            this.candidate = candidate;
        }

        public String getSdpMid() { return sdpMid; }
        public Integer getSdpMLineIndex() { return sdpMLineIndex; }
        public String getCandidate() { return candidate; }
    }

    private final Type type;
    private final String callId;
    private final Sdp sdp;
    private final IceCandidate candidate;

    public CallSignal(Type type, String callId, Sdp sdp, IceCandidate candidate) {
        this.type = type;
        this.callId = callId;
        this.sdp = sdp;
        this.candidate = candidate;
    }

    public Type getType() { return type; }
    public String getCallId() { return callId; }
    public Sdp getSdp() { return sdp; }
    public IceCandidate getCandidate() { return candidate; }

    public static CallSignal control(Type type, String callId) {
        return new CallSignal(type, callId, null, null);
    }
}
