package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Per-message translation presentation state. READY carries the translated
 * text; ERROR carries a stable server/API code for retryable rendering.
 */
public final class TranslationUiState {

    public enum Status { LOADING, READY, ERROR }

    @NonNull private final Status status;
    @Nullable private final String text;
    @Nullable private final String sourceLang;
    @NonNull private final String targetLang;
    @Nullable private final String errorCode;

    private TranslationUiState(
            @NonNull Status status, @Nullable String text, @Nullable String sourceLang,
            @NonNull String targetLang, @Nullable String errorCode) {
        this.status = status;
        this.text = text;
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
        this.errorCode = errorCode;
    }

    public static TranslationUiState loading(@NonNull String targetLang) {
        return new TranslationUiState(Status.LOADING, null, null, targetLang, null);
    }

    public static TranslationUiState ready(
            @NonNull String text, @Nullable String sourceLang, @NonNull String targetLang) {
        return new TranslationUiState(Status.READY, text, sourceLang, targetLang, null);
    }

    public static TranslationUiState error(@NonNull String targetLang, @Nullable String code) {
        return new TranslationUiState(Status.ERROR, null, null, targetLang, code);
    }

    @NonNull public Status getStatus() { return status; }
    @Nullable public String getText() { return text; }
    @Nullable public String getSourceLang() { return sourceLang; }
    @NonNull public String getTargetLang() { return targetLang; }
    @Nullable public String getErrorCode() { return errorCode; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TranslationUiState that)) return false;
        return status == that.status && Objects.equals(text, that.text)
                && Objects.equals(sourceLang, that.sourceLang)
                && Objects.equals(targetLang, that.targetLang)
                && Objects.equals(errorCode, that.errorCode);
    }

    @Override public int hashCode() {
        return Objects.hash(status, text, sourceLang, targetLang, errorCode);
    }
}
