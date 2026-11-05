package com.nexachat.message.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageDeletedPayload(UUID messageId, Instant deletedAt) {
}
