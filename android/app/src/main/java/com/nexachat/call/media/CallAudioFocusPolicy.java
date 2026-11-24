package com.nexachat.call.media;

import android.media.AudioManager;

/**
 * Pure policy for reacting to system audio-focus changes during a call. A lost or
 * transient focus mutes the microphone instead of failing the call, a duck request
 * only lowers the playout volume, and a regained focus restores whatever the loss
 * changed. The engine never treats a focus change as fatal.
 */
public final class CallAudioFocusPolicy {
    public enum FocusAction { NONE, MUTE_MIC, RESTORE_AUDIO, DUCK }

    private CallAudioFocusPolicy() { }

    public static FocusAction onFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            return FocusAction.MUTE_MIC;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            return FocusAction.DUCK;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            return FocusAction.RESTORE_AUDIO;
        }
        return FocusAction.NONE;
    }
}
