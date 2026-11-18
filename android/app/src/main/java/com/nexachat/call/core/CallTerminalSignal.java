package com.nexachat.call.core;

/**
 * Single decision for the terminal control signal a call end must still send to the server:
 * CANCEL while an outgoing call still rings, REJECT while an incoming call still rings, and
 * END once the call was accepted. A terminal state caused by an inbound server event is never
 * echoed back.
 */
public enum CallTerminalSignal {
    CANCEL(CallSignal.Type.CANCEL),
    REJECT(CallSignal.Type.REJECT),
    END(CallSignal.Type.END),
    NONE(null);

    private final CallSignal.Type signalType;

    CallTerminalSignal(CallSignal.Type signalType) {
        this.signalType = signalType;
    }

    /** The wire signal for this decision; null when the server must not be told. */
    public CallSignal.Type signalType() {
        return signalType;
    }

    /** Decides the outbound terminal signal for a session reaching a terminal state. */
    public static CallTerminalSignal forLocalFailure(CallState state, boolean initiatedByServer) {
        if (initiatedByServer) return NONE;
        if (state == null) return NONE;
        switch (state) {
            case OUTGOING_RINGING:
                return CANCEL;
            case INCOMING_RINGING:
            case PENDING_INCOMING:
                return REJECT;
            case CONNECTING:
            case ACTIVE:
            case RECONNECTING_NETWORK:
                return END;
            case IDLE:
            case ENDED:
            default:
                return NONE;
        }
    }
}
