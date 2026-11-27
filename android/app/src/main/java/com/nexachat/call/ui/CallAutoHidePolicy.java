package com.nexachat.call.ui;

import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.core.CallState;

/** Immersive controls are available once the video call is established. */
public final class CallAutoHidePolicy {
    private CallAutoHidePolicy() { }

    public static boolean shouldAutoHide(CallMediaType mediaType, CallState state) {
        return mediaType == CallMediaType.VIDEO && state == CallState.ACTIVE;
    }
}
