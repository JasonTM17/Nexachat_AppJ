package com.nexachat.call.core;

/** Lifecycle state visible to the Android call UI. */
public enum CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    ACTIVE,
    RECONNECTING_NETWORK,
    PENDING_INCOMING,
    ENDED
}
