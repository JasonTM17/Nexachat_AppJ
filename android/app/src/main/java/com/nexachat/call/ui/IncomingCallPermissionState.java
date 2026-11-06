package com.nexachat.call.ui;

/** Permission-dialog state that survives Activity recreation without dead-ending Accept. */
public final class IncomingCallPermissionState {
    private IncomingCallPermissionState() { }

    /** Android does not restore the app rationale; let Accept start a fresh request flow. */
    public static boolean pendingAcceptAfterRecreation(boolean savedPendingAccept) {
        return false;
    }

    public static boolean canStartAccept(boolean pendingAccept, boolean incomingCallRinging) {
        return !pendingAccept && incomingCallRinging;
    }
}
