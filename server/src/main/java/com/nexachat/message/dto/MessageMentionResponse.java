package com.nexachat.message.dto;

import java.util.UUID;

/** Structured member mention carried with a message response. */
public record MessageMentionResponse(UUID userId, int offset, int length) {
}
