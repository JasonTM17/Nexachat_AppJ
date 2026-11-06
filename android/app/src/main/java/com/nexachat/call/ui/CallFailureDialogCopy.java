package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallError;

/** Localized title and reason resources for a call failure dialog. */
public final class CallFailureDialogCopy {
    private final int titleResource;
    private final int reasonResource;

    private CallFailureDialogCopy(int titleResource, int reasonResource) {
        this.titleResource = titleResource;
        this.reasonResource = reasonResource;
    }

    public static CallFailureDialogCopy forCode(CallError.Code code) {
        if (code == null) return genericFailure();
        switch (code) {
            case BUSY:
                return new CallFailureDialogCopy(R.string.call_state_failed, R.string.call_state_busy);
            case PEER_OFFLINE:
                return new CallFailureDialogCopy(
                        R.string.call_state_failed, R.string.call_failure_peer_offline_reason);
            case NETWORK:
                return new CallFailureDialogCopy(
                        R.string.call_state_failed, R.string.call_failure_network_reason);
            case PERMISSION_DENIED:
                return forPermissionDenied();
            default:
                return genericFailure();
        }
    }

    public static CallFailureDialogCopy forPermissionDenied() {
        return new CallFailureDialogCopy(
                R.string.call_state_failed, R.string.call_mic_permission_needed);
    }

    private static CallFailureDialogCopy genericFailure() {
        return new CallFailureDialogCopy(
                R.string.call_state_failed, R.string.call_failure_generic_reason);
    }

    public int getTitleResource() {
        return titleResource;
    }

    public int getReasonResource() {
        return reasonResource;
    }
}
