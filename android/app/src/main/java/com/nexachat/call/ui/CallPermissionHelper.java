package com.nexachat.call.ui;

/** Permission decision rules shared by call entry points and unit tests. */
public final class CallPermissionHelper {
    public enum Decision { GRANTED, REQUEST, SHOW_RATIONALE }

    private CallPermissionHelper() { }

    public static Decision microphoneDecision(boolean granted, boolean showRationale) {
        if (granted) return Decision.GRANTED;
        return showRationale ? Decision.SHOW_RATIONALE : Decision.REQUEST;
    }

    public static Decision notificationDecision(int sdkInt, boolean granted) {
        return sdkInt < 33 || granted ? Decision.GRANTED : Decision.REQUEST;
    }

    public static Decision notificationDecision(int sdkInt, boolean granted, boolean showRationale) {
        if (sdkInt < 33 || granted) return Decision.GRANTED;
        return showRationale ? Decision.SHOW_RATIONALE : Decision.REQUEST;
    }

    public static boolean hasPhoneFallback(String phoneNumber) {
        return phoneNumber != null && !phoneNumber.trim().isEmpty();
    }
}
