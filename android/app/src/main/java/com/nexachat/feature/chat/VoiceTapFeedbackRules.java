package com.nexachat.feature.chat;

import com.nexachat.R;

/**
 * Feedback rules for the composer mic button. Recording is hold-to-record for sighted
 * users and click-to-toggle for accessibility; a tap that cannot start a clip still
 * tells the user what the gesture expects instead of ending silently.
 */
final class VoiceTapFeedbackRules {

    private VoiceTapFeedbackRules() {}

    /** The hint shown when a mic tap produces no recording. */
    static int quickTapHintMessageId() {
        return R.string.voice_hold_to_record;
    }

    /**
     * A consumed press that never started a recording finishes quietly; the hint
     * already surfaced when the press began, so no second message is needed.
     */
    static boolean finishWithoutRecordingIsQuiet(boolean cancel, boolean recording) {
        return !cancel && !recording;
    }
}
