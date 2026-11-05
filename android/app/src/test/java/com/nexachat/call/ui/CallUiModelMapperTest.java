package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallUiModelMapperTest {
    @Test public void outgoingRingingOffersCancelWithoutMediaControls() {
        CallUiModel model = CallUiModelMapper.map(session(CallState.OUTGOING_RINGING, null));
        assertEquals(R.string.call_state_calling, model.getTitleRes());
        assertEquals(0, model.getSubtitleRes());
        assertEquals(R.string.call_cancel, model.getHangUpLabelRes());
        assertTrue(model.isShowHangUp());
        assertFalse(model.isShowMute());
        assertFalse(model.isShowSpeaker());
    }

    @Test public void activeCallShowsTimerAndAudioControls() {
        CallUiModel model = CallUiModelMapper.map(session(CallState.ACTIVE, null));
        assertEquals(R.string.call_state_connected, model.getTitleRes());
        assertTrue(model.isShowTimer());
        assertTrue(model.isShowMute());
        assertTrue(model.isShowSpeaker());
        assertTrue(model.isShowHangUp());
    }

    @Test public void incomingAndConnectingStatesHaveTheExpectedActions() {
        CallUiModel incoming = CallUiModelMapper.map(session(CallState.INCOMING_RINGING, null));
        assertEquals(R.string.call_state_incoming, incoming.getTitleRes());
        assertEquals(R.string.call_decline, incoming.getHangUpLabelRes());
        assertFalse(incoming.isShowMute());

        CallUiModel connecting = CallUiModelMapper.map(session(CallState.CONNECTING, null));
        assertEquals(R.string.call_state_connecting, connecting.getTitleRes());
        assertTrue(connecting.isShowSpeaker());
        assertTrue(connecting.isShowHangUp());
        assertFalse(connecting.isShowTimer());
    }

    @Test public void idleStateDoesNotExposeCallControls() {
        CallUiModel idle = CallUiModelMapper.map(session(CallState.IDLE, null));
        assertEquals(R.string.call_state_ended, idle.getTitleRes());
        assertFalse(idle.isShowHangUp());
        assertFalse(idle.isShowSpeaker());
    }

    @Test public void endedReasonMapsToLocalizedOutcomeAndHidesControls() {
        CallUiModel model = CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.MISSED));
        assertEquals(R.string.call_state_no_answer, model.getTitleRes());
        assertFalse(model.isShowTimer());
        assertFalse(model.isShowHangUp());
    }

    @Test public void endedReasonsKeepTheirLocalizedOutcomeMapping() {
        assertEquals(R.string.call_state_busy,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.BUSY)).getTitleRes());
        assertEquals(R.string.call_state_declined,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.REJECTED)).getTitleRes());
        assertEquals(R.string.call_state_no_answer,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.MISSED)).getTitleRes());
        assertEquals(R.string.call_state_failed,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.FAILED)).getTitleRes());
        assertEquals(R.string.call_state_ended,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.CANCELLED)).getTitleRes());
        assertEquals(R.string.call_state_ended,
                CallUiModelMapper.map(session(CallState.ENDED, CallEndReason.COMPLETED)).getTitleRes());
    }

    private static CallSession session(CallState state, CallEndReason reason) {
        return new CallSession("call", "conversation", "peer", "Peer",
                CallSession.Direction.OUTGOING, state, reason, 1L, 2L, false, false);
    }
}
