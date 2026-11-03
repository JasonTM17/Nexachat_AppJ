package com.nexachat.message.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /conversations/{id}/pins} body: the message to pin. The message
 * must belong to that conversation.
 */
public record PinRequest(@NotNull UUID messageId) {
}
