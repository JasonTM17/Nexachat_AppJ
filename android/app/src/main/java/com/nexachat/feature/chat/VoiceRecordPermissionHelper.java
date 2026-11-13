package com.nexachat.feature.chat;

import com.nexachat.call.ui.CallPermissionHelper;
import com.nexachat.call.ui.CallPermissionHelper.Decision;

/** Mic permission flow for voice messages; shared with JVM tests. */
public final class VoiceRecordPermissionHelper {

    public enum TapAction { PROCEED, REQUEST_PERMISSION, SHOW_RATIONALE, OPEN_SETTINGS }

    public enum ResultAction { NONE, DENIED_SNACKBAR, PERMANENT_DENIAL_SNACKBAR }

    private VoiceRecordPermissionHelper() { }

    public static TapAction tapAction(boolean granted, boolean showRationale, boolean requestedBefore) {
        if (granted) {
            return TapAction.PROCEED;
        }
        if (requestedBefore && !showRationale) {
            return TapAction.OPEN_SETTINGS;
        }
        Decision decision = CallPermissionHelper.microphoneDecision(granted, showRationale);
        if (decision == Decision.SHOW_RATIONALE) {
            return TapAction.SHOW_RATIONALE;
        }
        return TapAction.REQUEST_PERMISSION;
    }

    public static ResultAction permissionResultAction(
            boolean granted, boolean showRationale, boolean requestedBefore) {
        if (granted) {
            return ResultAction.NONE;
        }
        if (requestedBefore && !showRationale) {
            return ResultAction.PERMANENT_DENIAL_SNACKBAR;
        }
        return ResultAction.DENIED_SNACKBAR;
    }

    public static boolean shouldBeginRecordingAfterPermissionGrant() {
        return false;
    }
}
