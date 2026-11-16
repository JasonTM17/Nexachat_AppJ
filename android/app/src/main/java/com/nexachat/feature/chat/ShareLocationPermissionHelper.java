package com.nexachat.feature.chat;

/**
 * Location-permission flow for the share sheet, mirroring the voice helper's
 * tap/result decision shape so the rules stay JVM-testable.
 */
public final class ShareLocationPermissionHelper {

    public enum TapAction { PROCEED, REQUEST_PERMISSION, SHOW_RATIONALE, OPEN_SETTINGS }

    public enum ResultAction { PROCEED, DENIED_SNACKBAR, PERMANENT_DENIAL }

    private ShareLocationPermissionHelper() {
    }

    public static TapAction tapAction(boolean granted, boolean showRationale, boolean requestedBefore) {
        if (granted) {
            return TapAction.PROCEED;
        }
        if (requestedBefore && !showRationale) {
            return TapAction.OPEN_SETTINGS;
        }
        return showRationale ? TapAction.SHOW_RATIONALE : TapAction.REQUEST_PERMISSION;
    }

    public static ResultAction resultAction(
            boolean granted, boolean showRationale, boolean requestedBefore) {
        if (granted) {
            return ResultAction.PROCEED;
        }
        return requestedBefore && !showRationale
                ? ResultAction.PERMANENT_DENIAL
                : ResultAction.DENIED_SNACKBAR;
    }
}
