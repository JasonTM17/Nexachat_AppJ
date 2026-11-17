package com.nexachat.call.core;

import java.util.Locale;

/** Maps server call-log outcomes to localized Android string resource keys. */
public final class CallLogFormatter {
    public static final String MISSED_KEY = "call_log_missed";
    public static final String DECLINED_KEY = "call_log_declined";
    public static final String CANCELLED_KEY = "call_log_cancelled";
    public static final String BUSY_KEY = "call_log_busy";
    public static final String FAILED_KEY = "call_log_failed";
    public static final String NEUTRAL_COMPLETED_KEY = "call_log_neutral_completed";
    public static final String OUTGOING_COMPLETED_KEY = "call_log_outgoing_completed";
    public static final String INCOMING_COMPLETED_KEY = "call_log_incoming_completed";
    public static final String VIDEO_MISSED_KEY = "call_log_video_missed";
    public static final String VIDEO_DECLINED_KEY = "call_log_video_declined";
    public static final String VIDEO_CANCELLED_KEY = "call_log_video_cancelled";
    public static final String VIDEO_BUSY_KEY = "call_log_video_busy";
    public static final String VIDEO_FAILED_KEY = "call_log_video_failed";
    public static final String VIDEO_NEUTRAL_COMPLETED_KEY = "call_log_video_neutral_completed";
    public static final String VIDEO_OUTGOING_COMPLETED_KEY = "call_log_video_outgoing_completed";
    public static final String VIDEO_INCOMING_COMPLETED_KEY = "call_log_video_incoming_completed";

    public static final class FormattedCallLog {
        private final String resourceKey;
        private final String callerUserId;
        private final Object[] args;

        private FormattedCallLog(String resourceKey, Object... args) {
            this(resourceKey, null, args);
        }

        private FormattedCallLog(String resourceKey, String callerUserId, Object[] args) {
            this.resourceKey = resourceKey;
            this.callerUserId = callerUserId;
            this.args = args.clone();
        }

        public String getResourceKey() {
            return resourceKey;
        }

        public Object[] getArgs() {
            return args.clone();
        }

        public String getCallerUserId() {
            return callerUserId;
        }
    }

    private CallLogFormatter() { }

    public static FormattedCallLog format(
            CallEndReason reason,
            long durationSeconds,
            CallSession.Direction direction) {
        return format(reason, durationSeconds, direction, CallMediaType.AUDIO);
    }

    public static FormattedCallLog format(
            CallEndReason reason,
            long durationSeconds,
            CallSession.Direction direction,
            CallMediaType mediaType) {
        boolean video = mediaType == CallMediaType.VIDEO;
        String failedKey = video ? VIDEO_FAILED_KEY : FAILED_KEY;
        if (reason == null) return new FormattedCallLog(failedKey);
        switch (reason) {
            case MISSED:
                return new FormattedCallLog(video ? VIDEO_MISSED_KEY : MISSED_KEY);
            case REJECTED:
                return new FormattedCallLog(video ? VIDEO_DECLINED_KEY : DECLINED_KEY);
            case CANCELLED:
                return new FormattedCallLog(video ? VIDEO_CANCELLED_KEY : CANCELLED_KEY);
            case BUSY:
                return new FormattedCallLog(video ? VIDEO_BUSY_KEY : BUSY_KEY);
            case FAILED:
                return new FormattedCallLog(failedKey);
            case COMPLETED:
                String key = direction == CallSession.Direction.OUTGOING
                        ? (video ? VIDEO_OUTGOING_COMPLETED_KEY : OUTGOING_COMPLETED_KEY)
                        : (video ? VIDEO_INCOMING_COMPLETED_KEY : INCOMING_COMPLETED_KEY);
                return new FormattedCallLog(key, formatDuration(durationSeconds));
            default:
                return new FormattedCallLog(failedKey);
        }
    }

    /**
     * Parses stable {@code CALL|REASON|seconds[|callerUserId][|mediaType]}
     * system-message content. A fifth {@code VIDEO} part marks a video call;
     * its absence or any other value keeps the audio wording.
     */
    public static FormattedCallLog fromSystemMessage(
            String content,
            CallSession.Direction direction) {
        if (content == null) return new FormattedCallLog(FAILED_KEY);
        String[] parts = content.split("\\|", -1);
        if (parts.length < 3 || parts.length > 5 || !"CALL".equals(parts[0])) {
            return new FormattedCallLog(FAILED_KEY);
        }
        String callerUserId = parts.length >= 4 ? parts[3] : null;
        if (parts.length >= 4 && callerUserId.trim().isEmpty()) {
            return new FormattedCallLog(FAILED_KEY);
        }
        CallMediaType mediaType = parts.length == 5 ? CallMediaType.parse(parts[4]) : CallMediaType.AUDIO;
        CallEndReason reason;
        long duration;
        try {
            reason = CallEndReason.valueOf(parts[1].toUpperCase(Locale.ROOT));
            duration = Math.max(0L, Long.parseLong(parts[2]));
        } catch (IllegalArgumentException exception) {
            return new FormattedCallLog(FAILED_KEY);
        }
        if (callerUserId == null && reason == CallEndReason.COMPLETED) {
            return new FormattedCallLog(
                    mediaType == CallMediaType.VIDEO
                            ? VIDEO_NEUTRAL_COMPLETED_KEY
                            : NEUTRAL_COMPLETED_KEY,
                    formatDuration(duration));
        }
        FormattedCallLog formatted = format(reason, duration, direction, mediaType);
        return new FormattedCallLog(formatted.resourceKey, callerUserId, formatted.args);
    }

    private static String formatDuration(long durationSeconds) {
        long safeSeconds = Math.max(0L, durationSeconds);
        long hours = safeSeconds / 3600L;
        long minutes = (safeSeconds % 3600L) / 60L;
        long seconds = safeSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", safeSeconds / 60L, seconds);
    }
}
