package com.nexachat.call.ui;

import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CallActivityLaunchPolicyTest {
    @Test
    public void restoredOutgoingActivityFinishesWhenManagerLostItsCall() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.FINISH,
                CallActivityLaunchPolicy.decide(
                        false, true, false, "call-1", "conversation-1", "peer-1", CallSession.idle()));
    }

    @Test
    public void freshOutgoingRequestMayStartWithoutAnExistingSession() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.START_OUTGOING,
                CallActivityLaunchPolicy.decide(
                        true, false, false, null, "conversation-1", "peer-1", CallSession.idle()));
    }

    @Test
    public void matchingLiveCallIsAttachedAfterRestoration() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.ATTACH_TO_LIVE_CALL,
                CallActivityLaunchPolicy.decide(
                        false, true, false, "call-1", "conversation-1", "peer-1", liveSession()));
    }

    @Test
    public void notificationOpenCannotStartCallAfterProcessDeath() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.FINISH,
                CallActivityLaunchPolicy.decide(
                        false, false, false, "call-1", "conversation-1", "peer-1", CallSession.idle()));
    }

    @Test
    public void restoredPendingOutgoingBeforeCallIdResumes() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.START_OUTGOING,
                CallActivityLaunchPolicy.decide(
                        true, true, true, null, "conversation-1", "peer-1", CallSession.idle()));
    }

    @Test
    public void restoredPendingOutgoingWithSavedCallIdDoesNotRestart() {
        assertEquals(
                CallActivityLaunchPolicy.Decision.FINISH,
                CallActivityLaunchPolicy.decide(
                        true, true, true, "call-1", "conversation-1", "peer-1", CallSession.idle()));
    }

    private static CallSession liveSession() {
        return new CallSession("call-1", "conversation-1", "peer-1", "Peer",
                CallSession.Direction.OUTGOING, CallState.OUTGOING_RINGING, null,
                1L, null, false, false);
    }
}
