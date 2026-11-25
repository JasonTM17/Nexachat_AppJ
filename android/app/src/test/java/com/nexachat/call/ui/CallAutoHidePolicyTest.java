package com.nexachat.call.ui;

import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.core.CallState;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallAutoHidePolicyTest {

    @Test
    public void liveVideoStatesAutoHide() {
        assertTrue(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.ACTIVE));
        assertTrue(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.CONNECTING));
        assertTrue(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.RECONNECTING_NETWORK));
    }

    @Test
    public void voiceCallsNeverAutoHide() {
        // There is no video behind the controls in a voice call — hiding them
        // would leave a blank screen for no benefit, unlike the big apps.
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.AUDIO, CallState.ACTIVE));
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.AUDIO, CallState.CONNECTING));
    }

    @Test
    public void ringingAndTerminalStatesNeverAutoHide() {
        // The user is still expected to act in these phases.
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.OUTGOING_RINGING));
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.INCOMING_RINGING));
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.ENDED));
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, CallState.IDLE));
    }

    @Test
    public void nullArgumentsNeverAutoHide() {
        assertFalse(CallAutoHidePolicy.shouldAutoHide(null, CallState.ACTIVE));
        assertFalse(CallAutoHidePolicy.shouldAutoHide(CallMediaType.VIDEO, null));
    }
}
