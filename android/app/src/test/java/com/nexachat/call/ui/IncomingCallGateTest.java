package com.nexachat.call.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;

public final class IncomingCallGateTest {

    @Test
    public void allClearWhenRingingAndEveryPushCheckPasses() {
        assertEquals(IncomingCallGate.Decision.ALL_CLEAR,
                IncomingCallGate.decide(ringing(), true, false, true, true));
        assertEquals(IncomingCallGate.Decision.ALL_CLEAR,
                IncomingCallGate.decide(pending(), true, true, true, true));
    }

    @Test
    public void noActiveSessionWhenSessionIsMissingOrIdle() {
        assertEquals(IncomingCallGate.Decision.NO_ACTIVE_SESSION,
                IncomingCallGate.decide(null, false, false, false, false));
        assertEquals(IncomingCallGate.Decision.NO_ACTIVE_SESSION,
                IncomingCallGate.decide(idle(), true, false, true, true));
    }

    @Test
    public void stalePushIntentFinishesTheScreen() {
        assertEquals(IncomingCallGate.Decision.STALE_PUSH_INTENT,
                IncomingCallGate.decide(ringing(), true, true, false, true));
    }

    @Test
    public void targetCallMismatchFinishesTheScreen() {
        assertEquals(IncomingCallGate.Decision.TARGET_CALL_MISMATCH,
                IncomingCallGate.decide(ringing(), false, false, true, true));
        assertEquals(IncomingCallGate.Decision.TARGET_CALL_MISMATCH,
                IncomingCallGate.decide(ringing(), false, true, true, true));
    }

    @Test
    public void stalePushIntentTakesPrecedenceOverMismatch() {
        assertEquals(IncomingCallGate.Decision.STALE_PUSH_INTENT,
                IncomingCallGate.decide(ringing(), false, true, false, true));
    }

    @Test
    public void stalePushSessionFinishesTheScreen() {
        assertEquals(IncomingCallGate.Decision.STALE_PUSH_SESSION,
                IncomingCallGate.decide(ringing(), true, true, true, false));
    }

    @Test
    public void sessionThatIsNoLongerRingingFinishesTheScreen() {
        assertEquals(IncomingCallGate.Decision.NOT_RINGING,
                IncomingCallGate.decide(connecting(), true, false, true, true));
        assertEquals(IncomingCallGate.Decision.NOT_RINGING,
                IncomingCallGate.decide(ended(), true, false, true, true));
    }

    @Test
    public void onStartGateKeepsEveryGateExceptTheRingingRecheck() {
        assertEquals(IncomingCallGate.Decision.ALL_CLEAR,
                IncomingCallGate.decideOnStart(connecting(), true, false, true, true));
        assertEquals(IncomingCallGate.Decision.STALE_PUSH_SESSION,
                IncomingCallGate.decideOnStart(ringing(), true, true, true, false));
        assertEquals(IncomingCallGate.Decision.TARGET_CALL_MISMATCH,
                IncomingCallGate.decideOnStart(ringing(), false, false, true, true));
        assertEquals(IncomingCallGate.Decision.NO_ACTIVE_SESSION,
                IncomingCallGate.decideOnStart(null, false, false, false, false));
        assertEquals(IncomingCallGate.Decision.NO_ACTIVE_SESSION,
                IncomingCallGate.decideOnStart(idle(), true, false, true, true));
    }

    @Test
    public void incomingNotificationPostsOnlyWhileRingingWithCurrentAdmission() {
        assertTrue(IncomingCallGate.shouldPostIncomingNotification(true, true));
        assertFalse(IncomingCallGate.shouldPostIncomingNotification(true, false));
        assertFalse(IncomingCallGate.shouldPostIncomingNotification(false, true));
        assertFalse(IncomingCallGate.shouldPostIncomingNotification(false, false));
    }

    private static CallSession session(CallState state) {
        return new CallSession("call-1234abcd", "conversation-1", "peer-1", "Peer",
                CallSession.Direction.INCOMING, state, null, 1L, null, false, false);
    }

    private static CallSession ringing() {
        return session(CallState.INCOMING_RINGING);
    }

    private static CallSession pending() {
        return session(CallState.PENDING_INCOMING);
    }

    private static CallSession connecting() {
        return session(CallState.CONNECTING);
    }

    private static CallSession ended() {
        return session(CallState.ENDED);
    }

    private static CallSession idle() {
        return CallSession.idle();
    }
}
