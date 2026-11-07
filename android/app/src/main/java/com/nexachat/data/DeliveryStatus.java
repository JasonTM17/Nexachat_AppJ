package com.nexachat.data;

/** Delivery state of a chat row. Pending outbox rows are SENDING or FAILED; history is SENT. */
public enum DeliveryStatus {
    SENT,
    SENDING,
    FAILED
}
