package com.nexachat.call.ui;

/** Permission decision rules shared by call entry points and unit tests. */
public final class CallPermissionHelper {
    public enum Decision { GRANTED, REQUEST, SHOW_RATIONALE, OPEN_SETTINGS }

    private CallPermissionHelper() { }

    public static Decision microphoneDecision(boolean granted, boolean showRationale) {
        if (granted) return Decision.GRANTED;
        return showRationale ? Decision.SHOW_RATIONALE : Decision.REQUEST;
    }

    /**
     * Camera is optional: a denied or permanently denied camera falls back to a
     * voice call instead of blocking the flow the way the microphone does.
     */
    public static Decision cameraDecision(
            boolean granted, boolean showRationale, boolean requestedBefore) {
        if (granted) return Decision.GRANTED;
        if (showRationale) return Decision.SHOW_RATIONALE;
        return requestedBefore ? Decision.OPEN_SETTINGS : Decision.REQUEST;
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
