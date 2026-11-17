package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;

import com.nexachat.R;
import org.junit.Test;

public final class CallPreviewIconTest {

    @Test
    public void missedVideoCallUsesVideoMissedGlyph() {
        assertEquals(R.drawable.ic_video_call_missed,
                CallPreviewIcon.forCall("CALL|MISSED|0|peer-1|VIDEO", true));
    }

    @Test
    public void missedVoiceCallKeepsPhoneMissedGlyph() {
        assertEquals(R.drawable.ic_call_missed,
                CallPreviewIcon.forCall("CALL|MISSED|0|peer-1", true));
    }

    @Test
    public void completedVideoCallKeepsCameraGlyph() {
        assertEquals(R.drawable.ic_videocam,
                CallPreviewIcon.forCall("CALL|COMPLETED|42|peer-1|VIDEO", false));
    }

    @Test
    public void completedVoiceCallUsesPhoneGlyph() {
        assertEquals(R.drawable.ic_call,
                CallPreviewIcon.forCall("CALL|COMPLETED|42|peer-1", false));
    }

    @Test
    public void nullContentFallsBackToVoiceGlyphs() {
        assertEquals(R.drawable.ic_call_missed, CallPreviewIcon.forCall(null, true));
        assertEquals(R.drawable.ic_call, CallPreviewIcon.forCall(null, false));
    }
}
