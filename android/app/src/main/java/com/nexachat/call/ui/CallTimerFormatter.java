package com.nexachat.call.ui;

import java.util.Locale;

/** Formats elapsed call time as the requested unbounded mm:ss display. */
public final class CallTimerFormatter {
    private CallTimerFormatter() { }

    public static String formatSeconds(long elapsedSeconds) {
        long safeSeconds = Math.max(0L, elapsedSeconds);
        return String.format(
                Locale.ROOT,
                "%02d:%02d",
                safeSeconds / 60L,
                safeSeconds % 60L);
    }
}
