package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.CallStatusDisplay;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test public void activeFirebaseProjectionRefinesOnlyTheNonterminalDisplay() {
        CallSession connecting = sessionWithIds(CallState.CONNECTING, null);
        CallStatusDisplay activeProjection = activeProjection();

        CallUiModel refined = CallUiModelMapper.map(connecting, activeProjection);

        assertEquals(R.string.call_status_accepted, refined.getTitleRes());
        assertEquals(R.string.call_hang_up, refined.getHangUpLabelRes());
        assertTrue(refined.isShowHangUp());
        assertTrue(refined.isShowSpeaker());
        assertFalse(refined.isShowTimer());
    }

    @Test public void firebaseActiveProjectionCannotReplaceTerminalServerOutcome() {
        CallSession ended = sessionWithIds(CallState.ENDED, CallEndReason.BUSY);

        CallUiModel model = CallUiModelMapper.map(ended, activeProjection());

        assertEquals(R.string.call_state_busy, model.getTitleRes());
        assertFalse(model.isShowHangUp());
        assertFalse(model.isShowMute());
        assertFalse(model.isShowSpeaker());
    }

    private static CallSession session(CallState state, CallEndReason reason) {
        return new CallSession("call", "conversation", "peer", "Peer",
                CallSession.Direction.OUTGOING, state, reason, 1L, 2L, false, false);
    }

    private static CallSession sessionWithIds(CallState state, CallEndReason reason) {
        return new CallSession("33333333-3333-4333-8333-333333333333",
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "22222222-2222-4222-8222-222222222222", "Peer",
                CallSession.Direction.OUTGOING, state, reason, 1L, 2L, false, false);
    }

    private static CallStatusDisplay activeProjection() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        payload.put("callerId", "11111111-1111-4111-8111-111111111111");
        payload.put("calleeId", "22222222-2222-4222-8222-222222222222");
        payload.put("state", "ACTIVE");
        payload.put("updatedAt", 1_000L);
        payload.put("expiresAt", 121_000L);
        return new FirebaseCallStatusDisplayAdapter(true)
                .adapt("33333333-3333-4333-8333-333333333333",
                        "33333333-3333-4333-8333-333333333333",
                        "11111111-1111-4111-8111-111111111111", payload, 2_000L)
                .orElseThrow();
    }
}
