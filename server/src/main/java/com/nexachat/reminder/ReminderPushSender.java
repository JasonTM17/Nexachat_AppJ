package com.nexachat.reminder;

import java.util.List;
import java.util.UUID;

/**
 * Minimal push hook for reminder fan-out.
 *
 * <p>{@code FcmPushService} exposes only message and call notifications, so
 * this package owns the small adapter rather than widening a package it does
 * not own. The production implementation uses the public {@code FcmGateway}
 * and device-token repositories.</p>
 */
public interface ReminderPushSender {

    /**
     * Sends one REMINDER data push to every device of {@code memberIds}.
     * Implementations must de-duplicate shared device tokens.
     */
    void send(ReminderDelivery reminder, List<UUID> memberIds);
}
