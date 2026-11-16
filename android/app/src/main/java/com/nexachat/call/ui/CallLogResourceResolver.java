package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallLogFormatter;
import com.nexachat.call.core.CallSession;

/** Resolves stable call-log keys to Android resources at the presentation edge. */
public final class CallLogResourceResolver {
    public static final class ResolvedCallLog {
        private final int resourceId;
        private final Object[] arguments;

        private ResolvedCallLog(int resourceId, Object[] arguments) {
            this.resourceId = resourceId;
            this.arguments = arguments.clone();
        }

        public int getResourceId() { return resourceId; }

        /** True when the call rang out unanswered; surfaces render it in the error colour. */
        public boolean isMissed() {
            return resourceId == R.string.call_log_missed
                    || resourceId == R.string.call_log_video_missed;
        }

        public Object[] getArguments() { return arguments.clone(); }
    }

    private CallLogResourceResolver() { }

    public static int resourceFor(String resourceKey) {
        if (CallLogFormatter.MISSED_KEY.equals(resourceKey)) return R.string.call_log_missed;
        if (CallLogFormatter.DECLINED_KEY.equals(resourceKey)) return R.string.call_log_declined;
        if (CallLogFormatter.CANCELLED_KEY.equals(resourceKey)) return R.string.call_log_cancelled;
        if (CallLogFormatter.BUSY_KEY.equals(resourceKey)) return R.string.call_log_busy;
        if (CallLogFormatter.FAILED_KEY.equals(resourceKey)) return R.string.call_log_failed;
        if (CallLogFormatter.OUTGOING_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_outgoing_completed;
        }
        if (CallLogFormatter.INCOMING_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_incoming_completed;
        }
        if (CallLogFormatter.NEUTRAL_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_neutral_completed;
        }
        if (CallLogFormatter.VIDEO_MISSED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_missed;
        }
        if (CallLogFormatter.VIDEO_DECLINED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_declined;
        }
        if (CallLogFormatter.VIDEO_CANCELLED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_cancelled;
        }
        if (CallLogFormatter.VIDEO_BUSY_KEY.equals(resourceKey)) {
            return R.string.call_log_video_busy;
        }
        if (CallLogFormatter.VIDEO_FAILED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_failed;
        }
        if (CallLogFormatter.VIDEO_OUTGOING_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_outgoing_completed;
        }
        if (CallLogFormatter.VIDEO_INCOMING_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_incoming_completed;
        }
        if (CallLogFormatter.VIDEO_NEUTRAL_COMPLETED_KEY.equals(resourceKey)) {
            return R.string.call_log_video_neutral_completed;
        }
        return R.string.call_log_failed;
    }

    public static int resourceFor(CallSession session, long durationSeconds) {
        if (session == null) return resourceFor(CallLogFormatter.FAILED_KEY);
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.format(
                session.getEndReason(), durationSeconds, session.getDirection(),
                session.getMediaType());
        return resourceFor(formatted.getResourceKey());
    }

    /** Resolves the server's stable call system-message token for the localized chat row. */
    public static ResolvedCallLog fromSystemMessage(String content, boolean outgoing) {
        CallSession.Direction direction = outgoing
                ? CallSession.Direction.OUTGOING : CallSession.Direction.INCOMING;
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                content, direction);
        return new ResolvedCallLog(
                resourceFor(formatted.getResourceKey()), formatted.getArgs());
    }
}
