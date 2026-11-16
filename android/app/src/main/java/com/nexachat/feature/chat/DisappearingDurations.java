package com.nexachat.feature.chat;

import android.content.Context;
import androidx.annotation.NonNull;
import com.nexachat.R;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server's allow-listed disappearing-message durations. The timer picker,
 * the header chip and the system-line localizer all read the same table so a
 * new server value only needs one entry here.
 */
public final class DisappearingDurations {

    /** Ordered picker values in seconds; {@code 0} is the explicit "off" value. */
    public static final int[] OPTIONS_SECONDS = {0, 3600, 86400, 604800, 2592000, 7776000};

    private DisappearingDurations() { }

    /** Localized label for a picker row or the header chip. Unknown values read "off". */
    @NonNull
    public static String label(@NonNull Context context, int seconds) {
        return switch (seconds) {
            case 3600 -> context.getString(R.string.disappearing_duration_1h);
            case 86400 -> context.getString(R.string.disappearing_duration_1d);
            case 604800 -> context.getString(R.string.disappearing_duration_7d);
            case 2592000 -> context.getString(R.string.disappearing_duration_30d);
            case 7776000 -> context.getString(R.string.disappearing_duration_90d);
            default -> context.getString(R.string.disappearing_off);
        };
    }

    /**
     * Maps the English duration embedded in a legacy server SYSTEM line back to
     * a localized label; unrecognized text passes through unchanged.
     */
    @NonNull
    public static String fromSystemLabel(@NonNull Context context, @NonNull String label) {
        Integer seconds = ENGLISH_TO_SECONDS.get(label.trim());
        return seconds == null ? label : label(context, seconds);
    }

    private static final Map<String, Integer> ENGLISH_TO_SECONDS = new LinkedHashMap<>();

    static {
        ENGLISH_TO_SECONDS.put("1 hour", 3600);
        ENGLISH_TO_SECONDS.put("1 day", 86400);
        ENGLISH_TO_SECONDS.put("7 days", 604800);
        ENGLISH_TO_SECONDS.put("30 days", 2592000);
        ENGLISH_TO_SECONDS.put("90 days", 7776000);
    }
}
