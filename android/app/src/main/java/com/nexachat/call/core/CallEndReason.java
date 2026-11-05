package com.nexachat.call.core;

/** Terminal reason reported by the server or inferred from a local failure. */
public enum CallEndReason {
    COMPLETED,
    REJECTED,
    CANCELLED,
    MISSED,
    BUSY,
    FAILED
}
