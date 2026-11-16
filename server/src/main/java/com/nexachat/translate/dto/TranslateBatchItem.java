package com.nexachat.translate.dto;

import java.util.UUID;

/**
 * One slot of a translate-batch response. {@code error} carries the stable
 * {@link com.nexachat.common.error.ApiErrorCode} string when the message could
 * not be translated; {@code text} is null in that case.
 */
public record TranslateBatchItem(
        UUID messageId,
        String text,
        String sourceLang,
        String targetLang,
        Boolean cached,
        String model,
        String error) {

    public static TranslateBatchItem translated(UUID messageId, String text, String sourceLang,
                                              String targetLang, boolean cached, String model) {
        return new TranslateBatchItem(messageId, text, sourceLang, targetLang, cached, model, null);
    }

    public static TranslateBatchItem failed(UUID messageId, String targetLang, String errorCode) {
        return new TranslateBatchItem(messageId, null, null, targetLang, null, null, errorCode);
    }
}
