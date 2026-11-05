package com.nexachat.message;

import com.nexachat.message.dto.MessageDeletedPayload;

import java.util.UUID;

public record MessageDeletedEvent(UUID conversationId, MessageDeletedPayload payload) {
}
