package com.nexachat.feature.chat;

import com.nexachat.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VoiceTapFeedbackRulesTest {

    @Test
    public void quickTapHintTeachesTheHoldGesture() {
        assertEquals(R.string.voice_hold_to_record, VoiceTapFeedbackRules.quickTapHintMessageId());
    }

    @Test
    public void finishWithoutAnActiveRecordingStaysQuiet() {
        assertTrue(VoiceTapFeedbackRules.finishWithoutRecordingIsQuiet(false));
    }

    @Test
    public void activeRecordingStillFinishes() {
        assertFalse(VoiceTapFeedbackRules.finishWithoutRecordingIsQuiet(true));
    }
}
