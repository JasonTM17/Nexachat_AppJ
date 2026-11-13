package com.nexachat.message.dto;

import java.time.Instant;
import java.util.UUID;

/** Lightweight timestamped identity used to page legacy recall changes. */
public record MessageDeletionChange(UUID messageId, Instant deletedAt) {
}
