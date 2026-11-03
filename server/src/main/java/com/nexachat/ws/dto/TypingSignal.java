package com.nexachat.ws.dto;

import java.util.UUID;

/** Payload of a {@code USER_TYPING} envelope: which member is typing. */
public record TypingSignal(UUID userId) {
}
