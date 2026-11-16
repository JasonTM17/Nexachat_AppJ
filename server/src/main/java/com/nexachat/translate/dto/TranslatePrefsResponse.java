package com.nexachat.translate.dto;

import java.util.UUID;

public record TranslatePrefsResponse(
        UUID conversationId,
        boolean autoTranslate,
        String targetLang) {
}
