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
        if (restoredActivity || !freshOutgoingRequest) return Decision.FINISH;
        return Decision.START_OUTGOING;
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
