package com.nexachat.notification;

import java.util.function.BooleanSupplier;

/** Durable, occurrence-keyed gate for notification side effects. */
public interface NotificationOccurrenceLedger {
    /** Returns true when this occurrence has already been delivered or is delivered now. */
    boolean deliverOnce(String occurrenceKey, BooleanSupplier publish);
}
