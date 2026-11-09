package com.nexachat.message;

import com.nexachat.message.dto.MessageResponse;

import java.util.UUID;

/**
 * Persist-before-broadcast signal (plan §11.3.1): published inside the send
 * transaction, consumed by {@link MessageBroadcastListener} AFTER COMMIT so a
 * {@code MESSAGE_CREATED} frame only ever carries a durable message.
 *
 * <p>{@code subjectUserId} is the member a {@code SYSTEM} event is about (the
 * removed member, or the member whose role changed). It exists only for push
 * gating — it is never persisted or put on the wire — and is {@code null} for
 * messages without a member subject.
 */
public record MessageCreatedEvent(UUID conversationId, MessageResponse message,
                                  UUID subjectUserId) {

    /** Convenience constructor for messages without a SYSTEM subject member. */
    public MessageCreatedEvent(UUID conversationId, MessageResponse message) {
        this(conversationId, message, null);
    }
}
