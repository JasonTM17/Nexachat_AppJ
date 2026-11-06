package com.nexachat.feature.media;

import java.util.Locale;

public final class FormatBytes {

    private FormatBytes() {
    }

    public static String format(Long bytes) {
        if (bytes == null || bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0);
    }
}
