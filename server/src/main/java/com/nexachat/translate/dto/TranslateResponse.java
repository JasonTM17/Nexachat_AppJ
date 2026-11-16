package com.nexachat.translate.dto;

public record TranslateResponse(
        String text,
        String sourceLang,
        String targetLang,
        boolean cached,
        String model) {
}
