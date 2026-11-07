package com.nexachat.core.database;

/** Outbox row states stored as strings so Room needs no type converter. */
public final class OutboxState {
    public static final String SENDING = "SENDING";
    public static final String FAILED = "FAILED";

    private OutboxState() {}
}
