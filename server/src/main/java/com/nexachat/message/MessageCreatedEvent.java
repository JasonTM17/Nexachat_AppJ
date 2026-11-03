package com.nexachat.message;

import com.nexachat.message.dto.MessageResponse;

import java.util.UUID;

/**
 * Persist-before-broadcast signal (plan §11.3.1): published inside the send
 * transaction, consumed by {@link MessageBroadcastListener} AFTER COMMIT so a
 * {@code MESSAGE_CREATED} frame only ever carries a durable message.
 */
public record MessageCreatedEvent(UUID conversationId, MessageResponse message) {
}
