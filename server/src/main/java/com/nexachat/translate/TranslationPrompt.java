package com.nexachat.translate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Built prompt plus the bookkeeping the output validator needs: the exact
 * untrusted JSON payload (for echo detection) and the source message id set
 * referenced by the context block.
 */
public record TranslationPrompt(
        String systemMessage,
        String userMessage,
        String untrustedJson,
        Map<UUID, Integer> contextIndexByMessageId,
        List<UUID> contextSenderIds) {
}
