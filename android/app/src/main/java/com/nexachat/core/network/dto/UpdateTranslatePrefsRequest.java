package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** Reflective Moshi bean for the translate-prefs PUT body. */
public final class UpdateTranslatePrefsRequest {
    private final boolean autoTranslate;
    private final String targetLang;

    public UpdateTranslatePrefsRequest() {
        this(false, null);
    }

    public UpdateTranslatePrefsRequest(boolean autoTranslate, @Nullable String targetLang) {
        this.autoTranslate = autoTranslate;
        this.targetLang = targetLang;
    }

    public boolean isAutoTranslate() {
        return autoTranslate;
    }

    @Nullable public String getTargetLang() {
        return targetLang;
    }
}
