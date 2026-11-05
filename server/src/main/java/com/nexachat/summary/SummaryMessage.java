package com.nexachat.summary;

import java.time.Instant;
import java.util.UUID;

public record SummaryMessage(
        UUID id,
        long seq,
        UUID senderId,
        String senderName,
        String text,
        Instant createdAt,
        Instant editedAt) {
}
