package com.nexachat.call.media;

import android.media.AudioManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The focus decision table the engine applies. Android constant values are inlined
 * at compile time, so the policy runs in a plain JVM test.
 */
public final class CallAudioFocusPolicyTest {

    @Test public void aPermanentFocusLossMutesTheMicrophone() {
        assertEquals(CallAudioFocusPolicy.FocusAction.MUTE_MIC,
                CallAudioFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS));
    }

    @Test public void aTransientFocusLossAlsoMutesTheMicrophone() {
        assertEquals(CallAudioFocusPolicy.FocusAction.MUTE_MIC,
                CallAudioFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT));
    }

    @Test public void aDuckRequestOnlyLowersTheVolumeInsteadOfMuting() {
        assertEquals(CallAudioFocusPolicy.FocusAction.DUCK,
                CallAudioFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK));
    }

    @Test public void aRegainedFocusRestoresMuteAndVolume() {
        assertEquals(CallAudioFocusPolicy.FocusAction.RESTORE_AUDIO,
                CallAudioFocusPolicy.onFocusChange(AudioManager.AUDIOFOCUS_GAIN));
    }

    @Test public void anUnknownFocusChangeIsIgnored() {
        assertEquals(CallAudioFocusPolicy.FocusAction.NONE,
                CallAudioFocusPolicy.onFocusChange(0));
    }
}
