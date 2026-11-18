package com.nexachat.call.ui;

import androidx.annotation.Nullable;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;

/** Pure decision for the incoming-call screen's finish gates, kept testable without Android. */
public final class IncomingCallGate {

    public enum Decision {
        ALL_CLEAR,
        NO_ACTIVE_SESSION,
        STALE_PUSH_INTENT,
        TARGET_CALL_MISMATCH,
        STALE_PUSH_SESSION,
        NOT_RINGING,
        PUSH_ADMISSION_FAILED
    }

    private IncomingCallGate() { }

    /**
     * Evaluates the onCreate gates: a missing or idle session, a stale push intent, a session
     * that is not the requested call, a stale push admission, or a session that stopped ringing.
     */
    public static Decision decide(
            @Nullable CallSession session,
            boolean matchesTargetCall,
            boolean hasPushAdmission,
            boolean pushIntentCurrent,
            boolean pushSessionCurrent) {
        if (session == null || session.getState() == CallState.IDLE) return Decision.NO_ACTIVE_SESSION;
        if (hasPushAdmission && !pushIntentCurrent) return Decision.STALE_PUSH_INTENT;
        if (!matchesTargetCall) return Decision.TARGET_CALL_MISMATCH;
        if (hasPushAdmission && !pushSessionCurrent) return Decision.STALE_PUSH_SESSION;
        if (session.getState() != CallState.INCOMING_RINGING
                && session.getState() != CallState.PENDING_INCOMING) return Decision.NOT_RINGING;
        return Decision.ALL_CLEAR;
    }

    /**
     * The onStart gate: identical checks, but a session that already left the ringing state is
     * owned by the session listener there, not by this gate.
     */
    public static Decision decideOnStart(
            @Nullable CallSession session,
            boolean matchesTargetCall,
            boolean hasPushAdmission,
            boolean pushIntentCurrent,
            boolean pushSessionCurrent) {
        Decision decision = decide(session, matchesTargetCall, hasPushAdmission,
                pushIntentCurrent, pushSessionCurrent);
        return decision == Decision.NOT_RINGING ? Decision.ALL_CLEAR : decision;
    }

    /**
     * The foreground notification bypass may keep a call visible only while the session is
     * still ringing AND its push admission is current; every other finish must clear it.
     */
    public static boolean shouldPostIncomingNotification(boolean ringing, boolean admissionCurrent) {
        return ringing && admissionCurrent;
    }
}
