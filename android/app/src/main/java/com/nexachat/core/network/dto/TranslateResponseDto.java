package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** Reflective Moshi bean for the single-message translate response. */
public final class TranslateResponseDto {
    private String text;
    private String sourceLang;
    private String targetLang;
    private boolean cached;
    private String model;

    public TranslateResponseDto() {
    }

    /** Local-cache constructor; {@code model} stays null for cache rows. */
    public TranslateResponseDto(String text, String sourceLang, String targetLang, boolean cached) {
        this.text = text;
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
        this.cached = cached;
    }

    @Nullable public String getText() {
        return text;
    }

    @Nullable public String getSourceLang() {
        return sourceLang;
    }

    @Nullable public String getTargetLang() {
        return targetLang;
    }

    public boolean isCached() {
        return cached;
    }

    @Nullable public String getModel() {
        return model;
    }
}
