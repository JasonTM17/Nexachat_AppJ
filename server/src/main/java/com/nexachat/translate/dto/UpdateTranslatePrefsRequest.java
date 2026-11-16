package com.nexachat.translate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Caller-scoped auto-translate preference for one conversation.
 * {@code targetLang} may be null/blank to fall back to the user's
 * {@code translate_target_lang} (and then to the client's app language).
 */
public record UpdateTranslatePrefsRequest(
        @NotNull Boolean autoTranslate,
        @Size(max = 16) String targetLang) {
}
