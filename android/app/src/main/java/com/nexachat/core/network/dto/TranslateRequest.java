package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;

/** Reflective Moshi bean for {@code POST /messages/{id}/translate}. */
public final class TranslateRequest {
    private final String targetLang;

    public TranslateRequest() {
        this(null);
    }

    public TranslateRequest(@NonNull String targetLang) {
        this.targetLang = targetLang;
    }

    @NonNull public String getTargetLang() {
        return targetLang;
    }
}
