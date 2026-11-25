package com.nexachat.call.ui;

import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.core.CallState;

/**
 * Pure-logic policy deciding whether the in-call controls may auto-hide after a
 * period without touches. Mirrors the big-app behavior (WhatsApp, Meet, Zoom):
 * the fade exists to uncover the video, so it only applies to live video calls —
 * voice calls keep their controls permanently visible because there is nothing
 * behind them, and ringing/terminal phases keep them because the user is still
 * expected to act.
 */
public final class CallAutoHidePolicy {
    private CallAutoHidePolicy() { }

    public static boolean shouldAutoHide(CallMediaType mediaType, CallState state) {
        if (mediaType != CallMediaType.VIDEO || state == null) return false;
        return state == CallState.ACTIVE
                || state == CallState.CONNECTING
                || state == CallState.RECONNECTING_NETWORK;
    }
}
