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
     * Sends one REMINDER data push to every current device of {@code memberIds}.
     * Implementations must de-duplicate shared device tokens.
     *
     * <p>Returns when the delivery policy is met: every attempted target
     * succeeded, every attempted target was terminally unregistered, or there
     * was no registered device. A retryable failure is reported only after
     * every target has been attempted. The occurrence identity stays tied to
     * the reminder and its due instant, so a later attempt is the same
     * occurrence.</p>
     *
     * @throws RetryableDeliveryException when at least one target still needs a retry
     */
    void send(ReminderDelivery reminder, List<UUID> memberIds);

    /**
     * At least one target had a retryable result. The occurrence must stay
     * claimed so the lease can run it again.
     */
    final class RetryableDeliveryException extends RuntimeException {
        RetryableDeliveryException() {
            super("retryable reminder delivery failure");
        }
    }
}
