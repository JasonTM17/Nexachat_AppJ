package com.nexachat.core.storage;

import java.util.Objects;

/** Session-scoped message recall settings received from the server. */
public final class RecallWindowConfig {
    public static final long DEFAULT_RECALL_WINDOW_SECONDS = 24L * 60L * 60L;
    public static final long MAX_RECALL_WINDOW_SECONDS = 7L * 24L * 60L * 60L;
    public static final RecallWindowConfig DEFAULT =
            new RecallWindowConfig(DEFAULT_RECALL_WINDOW_SECONDS, false);

    private final long recallWindowSeconds;
    private final boolean groupAdminMayRecallAfterWindow;

    public RecallWindowConfig(long recallWindowSeconds, boolean groupAdminMayRecallAfterWindow) {
        this.recallWindowSeconds = isValidWindow(recallWindowSeconds)
                ? recallWindowSeconds : DEFAULT_RECALL_WINDOW_SECONDS;
        this.groupAdminMayRecallAfterWindow = groupAdminMayRecallAfterWindow;
    }

    public static boolean isValidWindow(long seconds) {
        return seconds >= 60L && seconds <= MAX_RECALL_WINDOW_SECONDS;
    }

    public long getRecallWindowSeconds() {
        return recallWindowSeconds;
    }

    public boolean mayGroupAdminRecallAfterWindow() {
        return groupAdminMayRecallAfterWindow;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecallWindowConfig that
                && recallWindowSeconds == that.recallWindowSeconds
                && groupAdminMayRecallAfterWindow == that.groupAdminMayRecallAfterWindow;
    }

    @Override
    public int hashCode() {
        return Objects.hash(recallWindowSeconds, groupAdminMayRecallAfterWindow);
    }
}
