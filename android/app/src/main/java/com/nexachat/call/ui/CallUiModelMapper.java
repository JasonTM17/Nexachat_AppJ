package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;

/** Maps the manager snapshot into localized view resources and control visibility. */
public final class CallUiModelMapper {
    private CallUiModelMapper() { }

    public static CallUiModel map(CallSession session) {
        if (session == null) return ended(R.string.call_state_ended);
        switch (session.getState()) {
            case OUTGOING_RINGING:
                return model(R.string.call_state_calling, 0,
                        R.string.call_cancel, false, false, false, true);
            case INCOMING_RINGING:
                return model(R.string.call_state_incoming, 0,
                        R.string.call_decline, false, false, false, true);
            case CONNECTING:
                return model(R.string.call_state_connecting, 0,
                        R.string.call_hang_up, false, true, true, true);
            case ACTIVE:
                return model(R.string.call_state_connected, 0,
                        R.string.call_hang_up, true, true, true, true);
            case ENDED:
                return ended(titleFor(session.getEndReason()));
            case IDLE:
            default:
                return ended(R.string.call_state_ended);
        }
    }

    private static int titleFor(CallEndReason reason) {
        if (reason == null) return R.string.call_state_failed;
        switch (reason) {
            case BUSY: return R.string.call_state_busy;
            case MISSED: return R.string.call_state_no_answer;
            case REJECTED: return R.string.call_state_declined;
            case FAILED: return R.string.call_state_failed;
            case COMPLETED:
            case CANCELLED:
            default: return R.string.call_state_ended;
        }
    }

    private static CallUiModel ended(int titleRes) {
        return model(titleRes, 0, 0, false, false, false, false);
    }

    private static CallUiModel model(
            int titleRes,
            int subtitleRes,
            int hangUpLabelRes,
            boolean showTimer,
            boolean showMute,
            boolean showSpeaker,
            boolean showHangUp) {
        return new CallUiModel(titleRes, subtitleRes, hangUpLabelRes,
                showTimer, showMute, showSpeaker, showHangUp);
    }
}
