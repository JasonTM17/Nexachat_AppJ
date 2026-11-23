package com.nexachat.scheduled;

/**
 * Lifecycle of a {@code scheduled_messages} row (V46).
 *
 * <p>{@code PENDING} is the only live state; the dispatcher moves it to one
 * of the terminal states. {@code CANCELED} covers both an explicit user
 * cancel and administrative cancellation (account deletion writes it with
 * {@code failure_code='ACCOUNT_DELETED'}).</p>
 */
public enum ScheduledMessageStatus {
    PENDING,
    SENT,
    CANCELED,
    FAILED
}
