package com.nexachat.message;

import com.nexachat.message.dto.MessageResponse;

import java.util.UUID;

public record MessageUpdatedEvent(UUID conversationId, MessageResponse message) {
}
