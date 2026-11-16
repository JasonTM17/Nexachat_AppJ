package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;

/** Reflective Moshi bean for the per-conversation translate preferences. */
public final class TranslatePrefsDto {
    private String conversationId;
    private boolean autoTranslate;
    private String targetLang;

    @Nullable public String getConversationId() {
        return conversationId;
    }

    public boolean isAutoTranslate() {
        return autoTranslate;
    }

    @Nullable public String getTargetLang() {
        return targetLang;
    }
}
