package com.nexachat.call.ui;

import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;

/** Decides whether a call screen may start a call, attach to one, or close. */
public final class CallActivityLaunchPolicy {
    public enum Decision { START_OUTGOING, ATTACH_TO_LIVE_CALL, FINISH }

    private CallActivityLaunchPolicy() { }

    public static Decision decide(
            boolean freshOutgoingRequest,
            boolean restoredActivity,
            boolean pendingOutgoingStart,
            String requestedCallId,
            String conversationId,
            String peerUserId,
            CallSession session) {
        if (isLive(session)) {
            if (!matchesLiveCall(requestedCallId, conversationId, peerUserId, session)) {
                return Decision.FINISH;
            }
            return Decision.ATTACH_TO_LIVE_CALL;
        }
        if (restoredActivity) {
            // Permission dialogs recreate the activity before a call id exists.
            // A saved call id with no live session is process death: do not restart.
            if (pendingOutgoingStart && requestedCallId == null) {
                return Decision.START_OUTGOING;
            }
            return Decision.FINISH;
        }
        if (!freshOutgoingRequest) return Decision.FINISH;
        return Decision.START_OUTGOING;
    }

    /**
     * Returns the ENDED session a screen that started an outgoing call may adopt, or null.
     * {@code callIdBeforeLaunch} is the id of the terminal call the manager still held when
     * the screen opened.
     */
    public static CallSession endedSessionToAdopt(
            boolean startRequested, String callIdBeforeLaunch, CallSession latestSession) {
        if (startRequested && latestSession != null
                && latestSession.getState() == CallState.ENDED
                && !isStaleEnded(callIdBeforeLaunch, latestSession)) {
            return latestSession;
        }
        return null;
    }

    /**
     * Whether an ENDED update is the previous call's terminal state, not this launch's. The
     * manager keeps that state after a call ends, so a redial would otherwise show it and close.
     */
    public static boolean isStaleEnded(String callIdBeforeLaunch, CallSession session) {
        return callIdBeforeLaunch != null
                && session != null
                && session.getState() == CallState.ENDED
                && callIdBeforeLaunch.equals(session.getCallId());
    }

    public static boolean isLive(CallSession session) {
        if (session == null) return false;
        CallState state = session.getState();
        return state != CallState.IDLE && state != CallState.ENDED;
    }

    public static boolean matchesLiveCall(
            String requestedCallId,
            String conversationId,
            String peerUserId,
            CallSession session) {
        return isLive(session)
                && same(conversationId, session.getConversationId())
                && same(peerUserId, session.getPeerUserId())
                && (requestedCallId == null || same(requestedCallId, session.getCallId()));
    }

    private static boolean same(String left, String right) {
        return left != null && left.equals(right);
    }
}
