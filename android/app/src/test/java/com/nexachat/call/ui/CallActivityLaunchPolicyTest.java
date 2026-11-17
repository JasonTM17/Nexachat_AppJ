package com.nexachat.call.ui;

import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void redialIgnoresTheEndedSessionOfThePreviousCall() {
        assertNull(CallActivityLaunchPolicy.endedSessionToAdopt(
                true, "previous-call", endedSession("previous-call")));
    }

    @Test
    public void redialAdoptsTheEndedSessionOfItsOwnCall() {
        CallSession own = endedSession("new-call");
        assertSame(own, CallActivityLaunchPolicy.endedSessionToAdopt(
                true, "previous-call", own));
    }

    @Test
    public void firstCallAdoptsAnyEndedSessionOnceStarted() {
        CallSession own = endedSession("call-1");
        assertSame(own, CallActivityLaunchPolicy.endedSessionToAdopt(true, null, own));
    }

    @Test
    public void endedSessionIsNotAdoptedBeforeTheStartIsRequested() {
        assertNull(CallActivityLaunchPolicy.endedSessionToAdopt(
                false, null, endedSession("call-1")));
    }

    @Test
    public void liveOrMissingSessionsAreNeverAdoptedAsEnded() {
        assertNull(CallActivityLaunchPolicy.endedSessionToAdopt(true, null, liveSession()));
        assertNull(CallActivityLaunchPolicy.endedSessionToAdopt(true, null, null));
    }

    @Test
    public void staleEndedUpdateIsIgnoredWhileStartingARedial() {
        assertTrue(CallActivityLaunchPolicy.isStaleEnded(
                "previous-call", endedSession("previous-call")));
        assertFalse(CallActivityLaunchPolicy.isStaleEnded(
                "previous-call", endedSession("new-call")));
        assertFalse(CallActivityLaunchPolicy.isStaleEnded(
                "previous-call", liveSession()));
        assertFalse(CallActivityLaunchPolicy.isStaleEnded(null, endedSession("call-1")));
    }

    private static CallSession endedSession(String callId) {
        return new CallSession(callId, "conversation-1", "peer-1", "Peer",
                CallSession.Direction.OUTGOING, CallState.ENDED, CallEndReason.MISSED,
                1L, null, false, false);
    }

    private static CallSession liveSession() {
        return new CallSession("call-1", "conversation-1", "peer-1", "Peer",
                CallSession.Direction.OUTGOING, CallState.OUTGOING_RINGING, null,
                1L, null, false, false);
    }
}
