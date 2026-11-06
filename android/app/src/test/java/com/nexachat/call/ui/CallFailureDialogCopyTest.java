package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallError;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CallFailureDialogCopyTest {
    @Test public void busyUsesStableTitleAndBusyReason() {
        assertCopy(CallError.Code.BUSY, R.string.call_state_busy);
    }

    @Test public void peerOfflineHasDistinctReasonFromNoAnswer() {
        CallFailureDialogCopy copy = CallFailureDialogCopy.forCode(CallError.Code.PEER_OFFLINE);

        assertEquals(R.string.call_state_failed, copy.getTitleResource());
        assertEquals(R.string.call_failure_peer_offline_reason, copy.getReasonResource());
        assertNotEquals(R.string.call_state_no_answer, copy.getReasonResource());
    }

    @Test public void networkFailureExplainsConnectionProblem() {
        assertCopy(CallError.Code.NETWORK, R.string.call_failure_network_reason);
    }

    @Test public void microphonePermissionDenialExplainsRequiredPermission() {
        assertCopy(CallFailureDialogCopy.forPermissionDenied(),
                R.string.call_mic_permission_needed);
        assertCopy(CallError.Code.PERMISSION_DENIED, R.string.call_mic_permission_needed);
    }

    @Test public void unrecognizedOrMissingCodeUsesGenericReason() {
        assertCopy(CallError.Code.UNKNOWN, R.string.call_failure_generic_reason);
        assertCopy((CallError.Code) null, R.string.call_failure_generic_reason);
    }

    private static void assertCopy(CallError.Code code, int expectedReason) {
        assertCopy(CallFailureDialogCopy.forCode(code), expectedReason);
    }

    private static void assertCopy(CallFailureDialogCopy copy, int expectedReason) {
        assertEquals(R.string.call_state_failed, copy.getTitleResource());
        assertEquals(expectedReason, copy.getReasonResource());
    }
}
