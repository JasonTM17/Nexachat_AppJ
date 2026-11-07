package com.nexachat.core.database;

/** Values stored in {@code outbox.state}; no type converter is needed. */
public final class OutboxState {
    public static final String SENDING = "SENDING";
    public static final String FAILED = "FAILED";

    private OutboxState() {}
}
