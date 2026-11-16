package com.nexachat.translate;

import java.time.Instant;
import java.util.UUID;

/** Bounded projection of one message used by the translate path. */
public record TranslationMessage(
        UUID id,
        long seq,
        UUID senderId,
        String senderName,
        String content,
        long changeVersion,
        Instant createdAt) {
}
