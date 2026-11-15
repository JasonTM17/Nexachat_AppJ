package com.nexachat.core.database;

/** Values stored in {@code outbox.state}; no type converter is needed. */
public final class OutboxState {
    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String FAILED = "FAILED";
    /** FAILED on the terminal e2ee send pause specifically; the stamp tells
     *  the row apart from ordinary rejections sharing the same bubble. */
    public static final String FAILED_PAUSED = "FAILED_PAUSED";
    public static final String SENT = "SENT";

    private OutboxState() {}
}
