package com.nexachat.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TranslateRequest(
        @NotBlank @Size(max = 16) String targetLang) {
}
