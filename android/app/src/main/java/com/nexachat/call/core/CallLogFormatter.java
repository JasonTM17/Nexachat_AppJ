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
        if (reason == null) return new FormattedCallLog(FAILED_KEY);
        switch (reason) {
            case MISSED:
                return new FormattedCallLog(MISSED_KEY);
            case REJECTED:
                return new FormattedCallLog(DECLINED_KEY);
            case CANCELLED:
                return new FormattedCallLog(CANCELLED_KEY);
            case BUSY:
                return new FormattedCallLog(BUSY_KEY);
            case FAILED:
                return new FormattedCallLog(FAILED_KEY);
            case COMPLETED:
                String key = direction == CallSession.Direction.OUTGOING
                        ? OUTGOING_COMPLETED_KEY
                        : INCOMING_COMPLETED_KEY;
                return new FormattedCallLog(key, formatDuration(durationSeconds));
            default:
                return new FormattedCallLog(FAILED_KEY);
        }
    }

    /** Parses stable {@code CALL|REASON|seconds[|callerUserId]} system-message content. */
    public static FormattedCallLog fromSystemMessage(
            String content,
            CallSession.Direction direction) {
        if (content == null) return new FormattedCallLog(FAILED_KEY);
        String[] parts = content.split("\\|", -1);
        if ((parts.length != 3 && parts.length != 4) || !"CALL".equals(parts[0])) {
            return new FormattedCallLog(FAILED_KEY);
        }
        String callerUserId = parts.length == 4 ? parts[3] : null;
        if (parts.length == 4 && callerUserId.trim().isEmpty()) {
            return new FormattedCallLog(FAILED_KEY);
        }
        CallEndReason reason;
        long duration;
        try {
            reason = CallEndReason.valueOf(parts[1].toUpperCase(Locale.ROOT));
            duration = Math.max(0L, Long.parseLong(parts[2]));
        } catch (IllegalArgumentException exception) {
            return new FormattedCallLog(FAILED_KEY);
        }
        if (callerUserId == null && reason == CallEndReason.COMPLETED) {
            return new FormattedCallLog(NEUTRAL_COMPLETED_KEY, formatDuration(duration));
        }
        FormattedCallLog formatted = format(reason, duration, direction);
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
