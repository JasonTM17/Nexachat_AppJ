package com.nexachat.feature.inbox;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import com.nexachat.R;

/** Picks the inbox preview icon for a {@code CALL} system-message token. */
public final class CallPreviewIcon {

    private CallPreviewIcon() { }

    /**
     * Missed calls get the missed-arrow glyphs; the trailing {@code VIDEO}
     * media part swaps the phone for the camera in both states.
     */
    @DrawableRes
    public static int forCall(@Nullable String content, boolean missedCall) {
        boolean video = CallPreviewDirection.isVideoCall(content);
        if (missedCall) {
            return video ? R.drawable.ic_video_call_missed : R.drawable.ic_call_missed;
        }
        return video ? R.drawable.ic_videocam : R.drawable.ic_call;
    }
}
